// 站内登录信封加密的定向测试：只在本地生成临时密钥，不涉及真实账号。
// 运行：node --test tools/web/test_login_crypto.mjs
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  LoginCryptoError,
  base64UrlDecode,
  base64UrlEncode,
  encryptCredentials,
} from "../../web/login-crypto.js";

const decoder = new TextDecoder();

function publicSpkiBase64(publicKey) {
  return crypto.subtle.exportKey("spki", publicKey)
    .then(spki => Buffer.from(new Uint8Array(spki)).toString("base64"));
}

function assertCryptoError(action, message) {
  assert.throws(action, error => {
    assert.ok(error instanceof LoginCryptoError, `${message}：应为 LoginCryptoError`);
    assert.equal(error.code, "crypto_error", `${message}：错误码`);
    return true;
  });
}

test("encryptCredentials 加密的信封可用服务端私钥解开", async () => {
  const { publicKey, privateKey } = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt", "wrapKey", "unwrapKey"],
  );
  const spki = await publicSpkiBase64(publicKey);
  const credentials = { username: "20250001", password: "合成密码-abc123", nonce: "nonce_value_1" };

  const envelope = await encryptCredentials(spki, credentials);
  assert.deepEqual(Object.keys(envelope).sort(), ["ciphertext", "iv", "wrapped"]);
  for (const value of Object.values(envelope)) {
    assert.match(value, /^[A-Za-z0-9_-]+$/, "信封字段应为无填充 base64url");
  }

  const wrapped = base64UrlDecode(envelope.wrapped);
  const iv = base64UrlDecode(envelope.iv);
  const ciphertext = base64UrlDecode(envelope.ciphertext);
  assert.equal(wrapped.length, 256, "RSA-2048 包裹结果应为 256 字节");
  assert.equal(iv.length, 12, "AES-GCM IV 应为 12 字节");

  const aesKey = await crypto.subtle.unwrapKey(
    "raw",
    wrapped,
    privateKey,
    { name: "RSA-OAEP" },
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, aesKey, ciphertext);
  assert.deepEqual(JSON.parse(decoder.decode(plaintext)), credentials);
  assert.equal(JSON.parse(decoder.decode(plaintext)).nonce, "nonce_value_1");
});

test("每次加密使用不同的 AES 密钥与 IV", async () => {
  const { publicKey } = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt"],
  );
  const spki = await publicSpkiBase64(publicKey);
  const first = await encryptCredentials(spki, { username: "a", password: "b", nonce: "n1" });
  const second = await encryptCredentials(spki, { username: "a", password: "b", nonce: "n1" });
  assert.notEqual(first.wrapped, second.wrapped);
  assert.notEqual(first.iv, second.iv);
  assert.notEqual(first.ciphertext, second.ciphertext);
});

test("base64url 编解码可逆且分块处理大输入", () => {
  for (const bytes of [
    new Uint8Array(0),
    new Uint8Array([0]),
    new Uint8Array([251, 255, 254, 0, 1]),
    Uint8Array.from({ length: 100000 }, (_, index) => index % 256),
  ]) {
    const encoded = base64UrlEncode(bytes);
    assert.match(encoded, /^[A-Za-z0-9_-]*$/);
    assert.equal(encoded.includes("="), false);
    assert.deepEqual(base64UrlDecode(encoded), bytes);
  }
  assert.equal(base64UrlEncode(new Uint8Array([0xfb, 0xff])), "-_8");
  assert.deepEqual(base64UrlDecode("-_8"), new Uint8Array([0xfb, 0xff]));
});

test("base64url 解码拒绝非法字符、非法长度与非规范写法", () => {
  assertCryptoError(() => base64UrlDecode("A"), "长度为 1");
  assertCryptoError(() => base64UrlDecode("ab"), "末尾多余位");
  assertCryptoError(() => base64UrlDecode("a+b"), "标准 base64 字符");
  assertCryptoError(() => base64UrlDecode("a b"), "空白字符");
  assertCryptoError(() => base64UrlDecode("AAAA="), "带填充");
  assertCryptoError(() => base64UrlDecode("中文"), "非 ASCII");
  assertCryptoError(() => base64UrlDecode(null), "非字符串");
  assertCryptoError(() => base64UrlEncode("not bytes"), "非字节输入");
});

test("encryptCredentials 拒绝无效公钥与缺失字段", async () => {
  await assert.rejects(
    encryptCredentials("not-base64", { username: "a", password: "b", nonce: "n1" }),
    error => error instanceof LoginCryptoError && error.code === "crypto_error",
  );
  const { publicKey } = await crypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["encrypt", "decrypt"],
  );
  const spki = await publicSpkiBase64(publicKey);
  for (const invalid of [
    { username: "a" },
    { username: "a", password: "b" },
    { username: "a", password: "b", nonce: "bad nonce" },
  ]) {
    await assert.rejects(encryptCredentials(spki, invalid), error => {
      assert.ok(error instanceof LoginCryptoError);
      assert.equal(error.code, "crypto_error");
      return true;
    });
  }
});

test("没有 WebCrypto 时报告 insecure_context", async () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  assert.ok(descriptor?.configurable, "测试环境需要可替换的 globalThis.crypto");
  Object.defineProperty(globalThis, "crypto", { value: undefined, configurable: true });
  try {
    await assert.rejects(
      encryptCredentials("AAAA", { username: "a", password: "b", nonce: "n1" }),
      error => error instanceof LoginCryptoError && error.code === "insecure_context",
    );
  } finally {
    Object.defineProperty(globalThis, "crypto", descriptor);
  }
});
