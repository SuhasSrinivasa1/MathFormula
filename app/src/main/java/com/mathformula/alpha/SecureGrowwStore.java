package com.mathformula.alpha;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keys stay in Android Keystore; encrypted credential blobs stay in app-private preferences. */
final class SecureGrowwStore {
  private static final String KEY_ALIAS = "mathformula_groww_aes_v2";
  private final android.content.SharedPreferences pref;
  private final KeyStore store;
  private final Context appContext;
  Context context(){return appContext;}
  SecureGrowwStore(Context c) {
    appContext=c.getApplicationContext();
    pref = c.getSharedPreferences("groww_secrets_encrypted_v2", Context.MODE_PRIVATE);
    try { store=KeyStore.getInstance("AndroidKeyStore");store.load(null); }
    catch(Exception ex){throw new IllegalStateException("Android Keystore unavailable",ex);}
  }
  private SecretKey key() throws Exception {
    SecretKey k=(SecretKey)store.getKey(KEY_ALIAS,null);
    if(k!=null)return k;
    KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256).build());
    return gen.generateKey();
  }
  synchronized void put(String name,String value) {
    if(!name.matches("totp_token|totp_secret|access_token|server_viewer_token"))throw new IllegalArgumentException("Invalid secret name");
    try {
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key());
      String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
      String ciphertext=Base64.encodeToString(cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
      pref.edit().putString(name+"_iv",iv).putString(name+"_ct",ciphertext).apply();
    }catch(Exception ex){throw new IllegalStateException("Could not secure credentials",ex);}
  }
  synchronized String get(String name){
    String iv=pref.getString(name+"_iv",null), ct=pref.getString(name+"_ct",null);
    if(iv==null||ct==null)return "";
    try{
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
      return new String(cipher.doFinal(Base64.decode(ct,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }catch(Exception ex){return "";}
  }
  synchronized long sessionMs(){return pref.getLong("session_obtained_ms",0L);}
  synchronized void sessionMs(long when){pref.edit().putLong("session_obtained_ms",when).apply();}
  synchronized void clearSession(){pref.edit().remove("access_token_iv").remove("access_token_ct").remove("session_obtained_ms").apply();}
  synchronized void clearAll(){pref.edit().clear().apply();}
  boolean configured(){return !get("totp_token").isEmpty() && !get("totp_secret").isEmpty();}
}
