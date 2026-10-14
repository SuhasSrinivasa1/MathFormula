package com.mathformula.alpha;

import java.nio.ByteBuffer;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 SHA1 six-digit TOTP; compatible with Groww's token + Base32 secret. */
public final class TotpUtil {
  private TotpUtil(){}
  public static String generate(String secret,long epochSeconds){
    byte[] key=base32(secret);
    if(key.length<10)throw new IllegalArgumentException("TOTP secret invalid or too short");
    try{
      Mac mac=Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key,"HmacSHA1"));
      byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(epochSeconds/30L).array());
      int i=digest[digest.length-1]&15;
      int code=((digest[i]&127)<<24)|((digest[i+1]&255)<<16)|((digest[i+2]&255)<<8)|(digest[i+3]&255);
      return String.format(Locale.US,"%06d",code%1000000);
    }catch(Exception ex){throw new IllegalStateException("Could not generate TOTP",ex);}
  }
  static byte[] base32(String value){
    String clean=value.replace(" ","").replace("-","").replace("=","").toUpperCase(Locale.US);
    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
    int bits=0,acc=0;
    for(int i=0;i<clean.length();i++){
      char c=clean.charAt(i);
      int v=c>='A'&&c<='Z'?c-'A':c>='2'&&c<='7'?c-'2'+26:-1;
      if(v<0)throw new IllegalArgumentException("Groww secret must be Base32");
      acc=(acc<<5)|v;bits+=5;
      if(bits>=8){bits-=8;out.write((acc>>bits)&255);acc&=(1<<bits)-1;}
    }
    return out.toByteArray();
  }
}
