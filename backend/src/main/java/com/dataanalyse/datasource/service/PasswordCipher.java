package com.dataanalyse.datasource.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class PasswordCipher {
    private final SecretKeySpec key;
    public PasswordCipher(@Value("${dataanalyse.secret}") String secret) {
        try { key = new SecretKeySpec(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), 16), "AES"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try { byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv)); byte[] out=c.doFinal(value.getBytes(StandardCharsets.UTF_8)); byte[] all=new byte[iv.length+out.length]; System.arraycopy(iv,0,all,0,iv.length); System.arraycopy(out,0,all,iv.length,out.length); return Base64.getEncoder().encodeToString(all); }
        catch (Exception e) { throw new IllegalStateException("密码加密失败", e); }
    }
    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try { byte[] all=Base64.getDecoder().decode(value); byte[] iv=Arrays.copyOfRange(all,0,12); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv)); return new String(c.doFinal(Arrays.copyOfRange(all,12,all.length)),StandardCharsets.UTF_8); }
        catch (Exception e) { throw new IllegalStateException("密码解密失败", e); }
    }
}
