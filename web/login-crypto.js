// 站内登录的信封加密：把账号密码加密成一次性请求体的一部分。
// 这里只做加密，不保存、不记录、不实现解密；明文只在调用方本次提交的局部变量里存在。

const BASE64URL_PATTERN = /^[A-Za-z0-9_-]*$/;
// 分块转换，避免超大数组一次性 apply 或展开导致调用栈溢出。
const CHUNK_SIZE = 0x8000;
const TEXT = new TextEncoder();

export class LoginCryptoError extends Error {
  constructor(code) {
    super(code);
    this.name = "LoginCryptoError";
    this.code = code;
  }
}

function cryptoApi() {
  const api = globalThis.crypto;
  if (!api || typeof api.getRandomValues !== "function" || !api.subtle ||
      typeof api.subtle.importKey !== "function" ||
      typeof api.subtle.generateKey !== "function" ||
      typeof api.subtle.wrapKey !== "function" ||
      typeof api.subtle.encrypt !== "function") {
    throw new LoginCryptoError("insecure_context");
  }
  return api;
}

function bytesOf(value) {
  if (value instanceof Uint8Array) return value;
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  throw new LoginCryptoError("crypto_error");
}

function bytesToBinary(bytes) {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += CHUNK_SIZE) {
    const chunk = bytes.subarray(offset, offset + CHUNK_SIZE);
    binary += String.fromCharCode.apply(null, chunk);
  }
  return binary;
}

function binaryToBytes(binary) {
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

// SPKI 使用标准 base64（带填充），不是 base64url。
function decodeBase64(text) {
  if (typeof text !== "string" || !text) throw new LoginCryptoError("crypto_error");
  let binary;
  try {
    binary = atob(text);
  } catch {
    throw new LoginCryptoError("crypto_error");
  }
  return binaryToBytes(binary);
}

export function base64UrlEncode(value) {
  const binary = bytesToBinary(bytesOf(value));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64UrlDecode(text) {
  if (typeof text !== "string" || !BASE64URL_PATTERN.test(text) || text.length % 4 === 1) {
    throw new LoginCryptoError("crypto_error");
  }
  const base64 = text.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  let binary;
  try {
    binary = atob(padded);
  } catch {
    throw new LoginCryptoError("crypto_error");
  }
  const bytes = binaryToBytes(binary);
  // 只接受无填充的规范写法：非法字符、非法长度和末尾多余位都会在这里被拒绝。
  if (base64UrlEncode(bytes) !== text) throw new LoginCryptoError("crypto_error");
  return bytes;
}

// 使用服务端公钥加密本人账号与密码：
// 1. 导入 RSA-OAEP-SHA256 公钥；2. 生成一次性的 AES-256-GCM 密钥；
// 3. 用 RSA-OAEP 包裹 AES 密钥；4. 用 AES-GCM（随机 12 字节 IV）加密 JSON 明文。
// WebCrypto 要求包裹用的公钥带 wrapKey 用途，所以这里导入为 encrypt + wrapKey；
// 公钥本身只用于加密，不持有任何解密能力。
export async function encryptCredentials(spkiBase64, credentials) {
  const api = cryptoApi();
  const username = credentials?.username;
  const password = credentials?.password;
  const nonce = credentials?.nonce;
  if (typeof username !== "string" || typeof password !== "string" ||
      typeof nonce !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(nonce)) {
    throw new LoginCryptoError("crypto_error");
  }
  const spki = decodeBase64(spkiBase64);
  try {
    const publicKey = await api.subtle.importKey(
      "spki",
      spki,
      { name: "RSA-OAEP", hash: "SHA-256" },
      false,
      ["encrypt", "wrapKey"],
    );
    const aesKey = await api.subtle.generateKey(
      { name: "AES-GCM", length: 256 },
      true,
      ["encrypt"],
    );
    const wrapped = await api.subtle.wrapKey("raw", aesKey, publicKey, { name: "RSA-OAEP" });
    const iv = api.getRandomValues(new Uint8Array(12));
    const plaintext = TEXT.encode(JSON.stringify({ username, password, nonce }));
    let ciphertext;
    try {
      ciphertext = await api.subtle.encrypt({ name: "AES-GCM", iv }, aesKey, plaintext);
    } finally {
      plaintext.fill(0);
    }
    return {
      wrapped: base64UrlEncode(new Uint8Array(wrapped)),
      iv: base64UrlEncode(iv),
      ciphertext: base64UrlEncode(new Uint8Array(ciphertext)),
    };
  } catch (error) {
    if (error instanceof LoginCryptoError) throw error;
    throw new LoginCryptoError("crypto_error");
  }
}
