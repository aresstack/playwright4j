package com.aresstack.playwright4j.graal;

import org.graalvm.polyglot.HostAccess;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-backed subset of Node's crypto used by Playwright's client-certificate support
 * (generateSelfSignedCertificate: RSA key pair, PKCS#1 export, RSA-SHA256 signature) and by its
 * virtual WebAuthn authenticator (P-256 key pair, PKCS#8/SPKI/JWK export, ECDSA-SHA256 signature).
 *
 * The guest crypto.generateKeyPairSync returns KeyObjects whose .export({type:'pkcs1'}) yields the
 * encodings produced here; crypto.sign('sha256', data, privateKey) delegates to {@link #signSha256}.
 */
public final class JdkHostCrypto {

    static final char UNIT = '\u001e';

    private final Map<String, PrivateKey> privateKeys = new ConcurrentHashMap<String, PrivateKey>();
    private final AtomicLong ids = new AtomicLong(1);
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    /** Base64 of {@code length} bytes from SecureRandom; backs the runtime's crypto.randomBytes. */
    @HostAccess.Export
    public String randomBytes(int length) {
        byte[] bytes = new byte[Math.max(0, length)];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Generates an RSA key pair. Returns {@code keyId<UNIT>pkcs1PublicDerBase64<UNIT>pkcs1PrivatePem};
     * the private key is retained under keyId for later {@link #signSha256}.
     */
    @HostAccess.Export
    public String generateRsaKeyPair(int modulusBits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(modulusBits <= 0 ? 2048 : modulusBits);
            KeyPair pair = generator.generateKeyPair();

            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            byte[] pkcs1Public = encodePkcs1PublicKey(publicKey);

            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            String pkcs1PrivatePem = toPem("RSA PRIVATE KEY", encodePkcs1PrivateKey(privateKey));

            String keyId = "key-" + ids.getAndIncrement();
            privateKeys.put(keyId, privateKey);

            return keyId + UNIT + Base64.getEncoder().encodeToString(pkcs1Public) + UNIT + pkcs1PrivatePem;
        } catch (Exception exception) {
            throw new IllegalStateException("RSA key-pair generation failed", exception);
        }
    }

    /** Signs the Base64 data with RSASSA-PKCS1-v1_5 / SHA-256 using the retained private key. */
    @HostAccess.Export
    public String signSha256(String keyId, String base64Data) {
        PrivateKey privateKey = privateKeys.get(keyId);
        if (privateKey == null) {
            throw new IllegalStateException("Unknown crypto key id: " + keyId);
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(Base64.getDecoder().decode(base64Data));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("RSA-SHA256 signing failed", exception);
        }
    }

    /**
     * Generates a P-256 key pair for Playwright's virtual WebAuthn authenticator. Returns
     * {@code pkcs8PrivateDerBase64<UNIT>spkiPublicDerBase64<UNIT>x<UNIT>y}, x and y being the public
     * point's coordinates as unpadded Base64url (the JWK form).
     */
    @HostAccess.Export
    public String generateEcP256KeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), secureRandom);
            KeyPair pair = generator.generateKeyPair();
            ECPoint point = ((ECPublicKey) pair.getPublic()).getW();
            Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();
            return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + UNIT
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + UNIT
                    + base64Url.encodeToString(unsigned32(point.getAffineX())) + UNIT
                    + base64Url.encodeToString(unsigned32(point.getAffineY()));
        } catch (Exception exception) {
            throw new IllegalStateException("EC key-pair generation failed", exception);
        }
    }

    /**
     * Signs the Base64 data with SHA-256 and a PKCS#8 private key (DER, Base64): ECDSA with a DER
     * signature for EC keys, RSASSA-PKCS1-v1_5 for RSA keys, as Node's crypto.sign does.
     */
    @HostAccess.Export
    public String signSha256Pkcs8(String pkcs8Base64, String base64Data) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8Base64));
        try {
            PrivateKey privateKey;
            String algorithm;
            try {
                privateKey = KeyFactory.getInstance("EC").generatePrivate(spec);
                algorithm = "SHA256withECDSA";
            } catch (InvalidKeySpecException notEc) {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
                algorithm = "SHA256withRSA";
            }
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(privateKey);
            signature.update(Base64.getDecoder().decode(base64Data));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 signing with a PKCS#8 key failed", exception);
        }
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }

    @HostAccess.Export
    public void releaseKey(String keyId) {
        privateKeys.remove(keyId);
    }

    // --- Minimal DER encoding (just enough for PKCS#1 RSAPublicKey / RSAPrivateKey) ---

    private static byte[] encodePkcs1PublicKey(RSAPublicKey key) {
        // RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
        return derSequence(derInteger(key.getModulus()), derInteger(key.getPublicExponent()));
    }

    private static byte[] encodePkcs1PrivateKey(RSAPrivateCrtKey key) {
        // RSAPrivateKey ::= SEQUENCE { version, n, e, d, p, q, dP, dQ, qInv }
        return derSequence(
                derInteger(BigInteger.ZERO),
                derInteger(key.getModulus()),
                derInteger(key.getPublicExponent()),
                derInteger(key.getPrivateExponent()),
                derInteger(key.getPrimeP()),
                derInteger(key.getPrimeQ()),
                derInteger(key.getPrimeExponentP()),
                derInteger(key.getPrimeExponentQ()),
                derInteger(key.getCrtCoefficient()));
    }

    private static byte[] derInteger(BigInteger value) {
        byte[] content = value.toByteArray(); // already two's-complement, minimal, with sign byte
        return derTagged(0x02, content);
    }

    private static byte[] derSequence(byte[]... elements) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] element : elements) {
            body.write(element, 0, element.length);
        }
        return derTagged(0x30, body.toByteArray());
    }

    private static byte[] derTagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            byte[] lengthBytes = BigInteger.valueOf(length).toByteArray();
            int start = lengthBytes.length > 1 && lengthBytes[0] == 0 ? 1 : 0;
            int count = lengthBytes.length - start;
            out.write(0x80 | count);
            out.write(lengthBytes, start, count);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static String toPem(String label, byte[] der) {
        String base64 = Base64.getEncoder().encodeToString(der);
        StringBuilder builder = new StringBuilder();
        builder.append("-----BEGIN ").append(label).append("-----\n");
        for (int index = 0; index < base64.length(); index += 64) {
            builder.append(base64, index, Math.min(index + 64, base64.length())).append('\n');
        }
        builder.append("-----END ").append(label).append("-----\n");
        return builder.toString();
    }
}
