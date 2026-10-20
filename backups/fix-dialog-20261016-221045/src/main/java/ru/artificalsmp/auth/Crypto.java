package ru.artificalsmp.auth;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Crypto {
    private static final SecureRandom RANDOM=new SecureRandom();
    public static String random(int length,String alphabet) {
        StringBuilder b=new StringBuilder(length);
        for(int i=0;i<length;i++) b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return b.toString();
    }
    public static String password(int length,String alphabet) {
        for(;;) {
            String p=random(length,alphabet);
            if(p.chars().anyMatch(Character::isLetter) && p.chars().anyMatch(c->!Character.isLetterOrDigit(c))) return p;
        }
    }
    public static String hash(String password,int iterations) {
        byte[] salt=new byte[16]; RANDOM.nextBytes(salt);
        return iterations+":"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(password,salt,iterations));
    }
    public static boolean verify(String password,String hash) {
        try {
            String[] parts=hash.split(":");int rounds=Integer.parseInt(parts[0]);
            if(rounds<100000 || rounds>2000000) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]),derive(password,Base64.getDecoder().decode(parts[1]),rounds));
        } catch(RuntimeException e){return false;}
    }
    private static byte[] derive(String password,byte[] salt,int iterations) {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
        finally{spec.clearPassword();}
    }
}
