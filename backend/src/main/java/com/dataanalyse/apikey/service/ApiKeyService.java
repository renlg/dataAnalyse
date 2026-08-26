package com.dataanalyse.apikey.service;

import com.dataanalyse.apikey.entity.ApiKeyEntity;
import com.dataanalyse.apikey.repo.ApiKeyRepository;
import com.dataanalyse.common.BusinessException;
import com.dataanalyse.datasource.service.PasswordCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ApiKeyService {
    private final ApiKeyRepository repository; private final PasswordCipher cipher;
    public ApiKeyService(ApiKeyRepository r, PasswordCipher c){repository=r;cipher=c;}

    @Transactional public Map<String,Object> save(Long id, Map<String,Object> body){
        String name=str(body.get("name")); String type=str(body.get("type"));
        String baseUrl=str(body.get("baseUrl")); String apiKey=str(body.get("apiKey"));
        if(name==null||name.isBlank()) throw new BusinessException(400,"名称不能为空");
        if(!"llm".equals(type)&&!"taiwei".equals(type)) throw new BusinessException(400,"类型只能是 llm 或 taiwei");
        if(baseUrl==null||baseUrl.isBlank()) throw new BusinessException(400,"Base URL 不能为空");
        ApiKeyEntity e;
        if(id==null){
            if(apiKey==null||apiKey.isBlank()) throw new BusinessException(400,"API Key 不能为空");
            e=new ApiKeyEntity();
        } else {
            e=getEntity(id);
        }
        e.setName(name); e.setType(type); e.setBaseUrl(baseUrl);
        e.setModel(str(body.get("model"))); e.setRemark(str(body.get("remark")));
        if(apiKey!=null&&!apiKey.isBlank()&&!"***".equals(apiKey)) e.setApiKey(cipher.encrypt(apiKey));
        else if(id==null) throw new BusinessException(400,"API Key 不能为空");
        return view(repository.save(e));
    }

    public List<Map<String,Object>> list(){ return repository.findAll().stream().map(this::view).toList(); }

    public List<Map<String,Object>> options(){
        return repository.findAll().stream().map(e->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",e.getId());m.put("name",e.getName());m.put("type",e.getType());
            m.put("baseUrl",e.getBaseUrl());m.put("model",e.getModel());return m;
        }).toList();
    }

    public Map<String,Object> get(Long id){return view(getEntity(id));}

    @Transactional public void delete(Long id){ if(!repository.existsById(id)) throw new BusinessException(404,"API Key 不存在"); repository.deleteById(id); }

    public ApiKeyEntity getEntity(Long id){return repository.findById(id).orElseThrow(()->new BusinessException(404,"API Key 不存在"));}

    private Map<String,Object> view(ApiKeyEntity e){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",e.getId());m.put("name",e.getName());m.put("type",e.getType());
        m.put("baseUrl",e.getBaseUrl());m.put("apiKey","***");m.put("model",e.getModel());
        m.put("remark",e.getRemark());m.put("createdAt",e.getCreatedAt());return m;
    }

    private String str(Object o){return o==null?null:String.valueOf(o);}
}
