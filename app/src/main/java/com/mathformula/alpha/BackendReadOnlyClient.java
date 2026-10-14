package com.mathformula.alpha;

import org.json.JSONObject;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * The server token grants READ-ONLY research views. It is not a Groww API credential.
 * Only the explicitly configured HTTPS origin and predefined GET routes are allowed.
 */
final class BackendReadOnlyClient {
  private final String root,secret;
  BackendReadOnlyClient(String base,String bearer) {
    try{
      URL url=new URL(base);
      if(!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()||
         url.getUserInfo()!=null || (url.getPath()!=null&&!url.getPath().replace("/","").isEmpty()) ||
         url.getQuery()!=null || url.getRef()!=null)
        throw new IllegalArgumentException("Enter a plain HTTPS domain URL only");
      if(bearer==null||bearer.length()<32)throw new IllegalArgumentException("Server viewer key is missing (32+ chars)");
      root="https://"+url.getAuthority();secret=bearer;
    }catch(java.net.MalformedURLException ex){throw new IllegalArgumentException("Invalid HTTPS backend URL");}
  }
  JSONObject get(String route) throws Exception {
    if(!("/v1/status".equals(route)||"/v1/watchlist".equals(route)||
        "/v1/preclose".equals(route)||"/v1/misses".equals(route)||"/v1/events".equals(route)||"/v1/export".equals(route)))
      throw new SecurityException("Unknown read-only research API path");
    HttpURLConnection conn=(HttpURLConnection)new URL(root+route).openConnection();
    try{
      conn.setRequestMethod("GET");
      conn.setInstanceFollowRedirects(false);
      conn.setRequestProperty("Authorization","Bearer "+secret);
      conn.setRequestProperty("Accept","application/json");
      conn.setConnectTimeout(8000);conn.setReadTimeout(12000);
      int code=conn.getResponseCode();
      if(code==401)throw new SecurityException("Read-only viewer token rejected");
      if(code<200||code>=300)throw new IllegalStateException("Scanner API HTTP "+code);
      try(InputStream stream=conn.getInputStream();
          BufferedReader in=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
        StringBuilder out=new StringBuilder();String line;
        while((line=in.readLine())!=null){
          out.append(line);
          if(out.length()>1500000)throw new IllegalStateException("Scanner response too large");
        }
        JSONObject j=new JSONObject(out.toString());
        if("/v1/watchlist".equals(route)&&!"Alpha-30-v1.0".equals(j.optString("modelVersion")))
          throw new IllegalStateException("Scanner formula version mismatch");
        return j;
      }
    }finally{conn.disconnect();}
  }
}
