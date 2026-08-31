package com.dataanalyse.llm;

import com.dataanalyse.common.BusinessException;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
public class LlmClient {
    private final RestClient client;
    public LlmClient(){
        // 连接超时 10s + 读取超时 60s：防止上游挂起时 run 永远 running
        JdkClientHttpRequestFactory factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client=RestClient.builder().requestFactory(factory).build();
    }
    @SuppressWarnings("unchecked")
    public String chat(String baseUrl,String apiKey,String model,List<Map<String,String>> messages){
        if(baseUrl==null||baseUrl.isBlank()||model==null||model.isBlank()) throw new BusinessException(400,"模型地址和模型名称不能为空");
        String trimmed=baseUrl.replaceAll("/+$","");
        String url=trimmed.endsWith("/v1")?trimmed+"/chat/completions":trimmed+"/v1/chat/completions";
        try {
            Map<String,Object> response=client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .headers(h->{if(apiKey!=null&&!apiKey.isBlank())h.setBearerAuth(apiKey);})
                    .body(Map.of("model",model,"messages",messages,"stream",false)).retrieve().body(Map.class);
            List<Map<String,Object>> choices=(List<Map<String,Object>>)response.get("choices");
            if(choices==null||choices.isEmpty())throw new BusinessException(502,"模型未返回内容");
            Map<String,Object> message=(Map<String,Object>)choices.get(0).get("message"); return String.valueOf(message.get("content"));
        } catch(BusinessException e){throw e;} catch(Exception e){throw new BusinessException(502,"模型调用失败："+e.getMessage());}
    }
}
