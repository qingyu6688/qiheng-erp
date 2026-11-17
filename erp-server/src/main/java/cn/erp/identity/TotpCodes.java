package cn.erp.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 使用 30 秒步长，前后各允许一个步长。 */
final class TotpCodes {
    private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM=new SecureRandom();

    private TotpCodes() {}

    static String secret() {
        byte[] bytes=random(20);
        var encoded=new StringBuilder(32);
        for (int offset=0;offset<bytes.length;offset+=5) {
            long block=0;
            for (int index=0;index<5;index++) block=block<<8 | Byte.toUnsignedInt(bytes[offset+index]);
            for (int shift=35;shift>=0;shift-=5) encoded.append(ALPHABET.charAt((int)(block>>>shift)&31));
        }
        return encoded.toString();
    }

    static byte[] random(int size) {
        byte[] bytes=new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String recovery() {
        String code=HexFormat.of().withUpperCase().formatHex(random(10));
        return code.substring(0,5)+"-"+code.substring(5,10)+"-"+code.substring(10,15)+"-"+code.substring(15);
    }

    static String canonicalRecovery(String code) {
        return code == null ? "" : code.strip().replace("-", "").toUpperCase(Locale.ROOT);
    }

    static long matchingStep(String secret,String supplied,long current,long lastUsed) {
        if (supplied==null || !supplied.matches("[0-9]{6}")) return -1;
        for (long candidate : new long[]{current,current-1,current+1}) {
            if (candidate>lastUsed && candidate>=0 && MessageDigest.isEqual(code(secret,candidate).getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII))) return candidate;
        }
        return -1;
    }

    static String code(String secret,long step) {
        byte[] key=new byte[20];
        for (int offset=0;offset<secret.length();offset+=8) {
            long block=0;
            for (int index=0;index<8;index++) block=block<<5 | ALPHABET.indexOf(secret.charAt(offset+index));
            for (int index=0;index<5;index++) key[offset/8*5+index]=(byte)(block>>>(32-index*8));
        }
        try {
            Mac mac=Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key,"HmacSHA1"));
            byte[] digest=mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset=digest[digest.length-1]&15;
            int binary=ByteBuffer.wrap(digest,offset,Integer.BYTES).getInt()&Integer.MAX_VALUE;
            return String.format(Locale.ROOT,"%06d",binary%1_000_000);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("运行环境缺少 TOTP 所需算法",failure);
        }
    }
}
