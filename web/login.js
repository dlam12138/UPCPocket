// 站内登录对话框：在本站内收集学校账号，用服务端公钥加密后提交。
// 密码只存在于本次提交的局部变量和输入框里，提交结束立即清空，不写入存储或网址。
import { LoginCryptoError, base64UrlEncode, encryptCredentials } from "./login-crypto.js";

const branchInfo = {
  tangdaowan: {
    title: "唐岛湾图书馆",
    official: "https://libspace.upc.edu.cn/libseat/",
  },
  east: {
    title: "古镇口东区图书馆",
    official: "https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453",
  },
  qiushi: {
    title: "求是图书馆",
    official: "https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258",
  },
};

const errorMessages = {
  invalid_credentials: "学校账号或密码不正确，请核对后重试。",
  interaction_required: "学校要求验证码或额外验证，请前往学校官方页面完成登录。",
  rate_limited: "登录尝试过于频繁，请稍后再试。",
  invalid_request: "登录信息无效，请重新输入后重试。",
  upstream_unavailable: "学校服务暂时不可用，请稍后再试。",
  network_unavailable: "无法连接学校网络，请稍后再试。",
};

const NETWORK_MESSAGE = "无法连接站内服务，请稍后再试。";
const REQUEST_TIMEOUT = 20000;

class LoginRequestError extends Error {}

function createRequest() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);
  const done = () => clearTimeout(timer);
  const options = {
    mode: "same-origin",
    credentials: "same-origin",
    cache: "no-store",
    referrerPolicy: "same-origin",
    redirect: "error",
    signal: controller.signal,
  };
  return { options, done };
}

function makeNonce() {
  const api = globalThis.crypto;
  if (!api || typeof api.getRandomValues !== "function") {
    throw new LoginCryptoError("insecure_context");
  }
  return base64UrlEncode(api.getRandomValues(new Uint8Array(32)));
}

function isJson(response) {
  return response.headers.get("Content-Type")?.split(";")[0].trim() === "application/json";
}

async function requestJson(url, options) {
  const request = createRequest();
  try {
    const response = await fetch(url, { ...request.options, ...options });
    const body = isJson(response) ? await response.json().catch(() => null) : null;
    return { response, body };
  } finally {
    request.done();
  }
}

export function initializeLogin({ onSignedIn } = {}) {
  const dialog = document.querySelector("#library-login");
  const form = document.querySelector("#library-login-form");
  const title = document.querySelector("#library-login-title");
  const branchLabel = document.querySelector("#library-login-branch");
  const username = document.querySelector("#library-login-username");
  const password = document.querySelector("#library-login-password");
  const consent = document.querySelector("#library-login-consent");
  const status = document.querySelector("#library-login-status");
  const submit = document.querySelector("#library-login-submit");
  const cancel = document.querySelector("#library-login-cancel");
  const official = document.querySelector("#library-login-official");

  let branch = null;
  let submitting = false;

  function setStatus(message, { error = false } = {}) {
    status.textContent = message;
    if (error) status.dataset.state = "error";
    else delete status.dataset.state;
  }

  function close() {
    password.value = "";
    setStatus("");
    if (dialog.open) dialog.close();
  }

  function open(nextBranch) {
    const info = branchInfo[nextBranch];
    if (!info || submitting) return;
    branch = nextBranch;
    title.textContent = `登录${info.title}`;
    branchLabel.textContent = `馆区：${info.title}`;
    official.href = info.official;
    password.value = "";
    setStatus("");
    if (dialog.open) dialog.close();
    dialog.showModal();
    username.focus({ preventScroll: true });
  }

  async function signIn() {
    if (submitting || !branch) return;
    if (!consent.checked) {
      setStatus("请先阅读并同意上述说明。", { error: true });
      return;
    }
    const account = username.value.trim();
    if (!account || !password.value) {
      setStatus("请输入学校账号和密码。", { error: true });
      return;
    }
    const signingInBranch = branch;
    // 明文只保存在本次提交的局部变量里，提交结束即随作用域释放，密码输入框同时清空。
    submitting = true;
    submit.disabled = true;
    cancel.disabled = true;
    setStatus("正在登录…");
    try {
      const { response: keyResponse, body: key } = await requestJson("./api/auth/key", {
        headers: { Accept: "application/json" },
      });
      if (!keyResponse.ok || typeof key?.kid !== "string" || !key.kid ||
          typeof key?.spki !== "string" || !key.spki) {
        throw new LoginRequestError("key");
      }
      // 浏览器生成一次性 nonce：服务端只接受首次登记的密文，重放会被拒绝；
      // 公钥端点因此不写服务端状态。
      const nonce = makeNonce();
      const credentials = { username: account, password: password.value, nonce };
      const encrypted = await encryptCredentials(key.spki, credentials);
      const { response, body } = await requestJson("./api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          kid: key.kid,
          nonce,
          wrapped: encrypted.wrapped,
          iv: encrypted.iv,
          ciphertext: encrypted.ciphertext,
          branch: signingInBranch,
          consent: true,
        }),
      });
      if (response.ok && body?.state === "ready" && body.branch === signingInBranch) {
        close();
        onSignedIn?.(signingInBranch);
        return;
      }
      setStatus(errorMessages[body?.error] ?? "登录暂时失败，请稍后再试。", { error: true });
    } catch (error) {
      if (error instanceof LoginCryptoError) {
        setStatus(error.code === "insecure_context"
          ? "当前页面不是安全连接，无法加密登录信息。请使用 HTTPS 打开后重试。"
          : "登录信息加密失败，请刷新页面后重试。", { error: true });
      } else {
        setStatus(NETWORK_MESSAGE, { error: true });
      }
    } finally {
      password.value = "";
      submitting = false;
      submit.disabled = false;
      cancel.disabled = false;
    }
  }

  dialog.addEventListener("cancel", event => {
    if (submitting) event.preventDefault();
  });
  dialog.addEventListener("close", () => {
    password.value = "";
  });
  form.addEventListener("submit", event => {
    event.preventDefault();
    signIn();
  });
  cancel.addEventListener("click", close);

  return { open, close };
}
