package com.upcpocket.app.update

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 更新清单签名：固定 SHA256withECDSA / P-256。
 *
 * 验证的是 更新清单原始 UTF-8 字节，而不是反序列化再序列化的 JSON。
 * 签名算法和公钥都由应用内置，清单不能自行选择算法、公钥或下载主机。
 */
class UpdateSignatureVerifier(publicKeyBase64: String) {
    private val publicKey: PublicKey = decodeP256PublicKey(publicKeyBase64)

    fun verify(payload: ByteArray, signatureDer: ByteArray): Boolean {
        if (signatureDer.size !in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) return false
        if (!isStrictDerEcdsaSignature(signatureDer)) return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(payload)
                verify(signatureDer)
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val MIN_SIGNATURE_BYTES = 64
        private const val MAX_SIGNATURE_BYTES = 72

        fun decodeP256PublicKey(base64: String): PublicKey {
            require(base64.isNotBlank()) { "empty update public key" }
            val der = Base64.getDecoder().decode(base64.trim())
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            require(key is ECPublicKey) { "update key must be EC" }
            val params = key.params
            require(params.curve.field.fieldSize == 256) { "update key must use P-256" }
            require(params.order.bitLength() == 256) { "update key must use P-256 order" }
            return key
        }

        /**
         * 只接受一个完整的 DER SEQUENCE { r INTEGER, s INTEGER }。
         *
         * 部分 JCA provider 会忽略签名尾部附加字节；这里先把“恰好一条、无尾随数据”
         * 固定下来，避免把非规范数据当作有效清单签名。
         */
        fun isStrictDerEcdsaSignature(signature: ByteArray): Boolean {
            var offset = 0

            fun readLength(): Int {
                if (offset >= signature.size) return -1
                val first = signature[offset].toInt() and 0xFF
                offset += 1
                if (first and 0x80 == 0) return first
                val count = first and 0x7F
                if (count !in 1..2 || offset + count > signature.size) return -1
                var value = 0
                repeat(count) {
                    value = (value shl 8) or (signature[offset].toInt() and 0xFF)
                    offset += 1
                }
                return if (value < 0x80) -1 else value
            }

            fun readInteger(): Boolean {
                if (offset >= signature.size || (signature[offset].toInt() and 0xFF) != 0x02) {
                    return false
                }
                offset += 1
                val length = readLength()
                if (length !in 1..33 || offset + length > signature.size) return false
                val start = offset
                val first = signature[start].toInt() and 0xFF
                if (first and 0x80 != 0) return false
                if (length > 1 && first == 0 && (signature[start + 1].toInt() and 0x80) == 0) {
                    return false
                }
                val significantStart = if (length > 1 && first == 0) start + 1 else start
                if (significantStart >= start + length) return false
                if ((significantStart until start + length).all { signature[it].toInt() == 0 }) {
                    return false
                }
                offset += length
                return true
            }

            if (signature.size < 8 || (signature[0].toInt() and 0xFF) != 0x30) return false
            offset = 1
            val sequenceLength = readLength()
            if (sequenceLength < 0 || offset + sequenceLength != signature.size) return false
            val sequenceEnd = offset + sequenceLength
            if (!readInteger()) return false
            if (!readInteger()) return false
            return offset == sequenceEnd
        }
    }
}
