package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.privacy.ClientIdentityRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"local-dev","dev29","customer"})
public class Dm8ClientIdentityRepository implements ClientIdentityRepository {
    private final ClientIdentityMapper mapper;
    public Dm8ClientIdentityRepository(ClientIdentityMapper mapper){this.mapper=mapper;}
    @Override @Transactional(readOnly=true)
    public List<String> enabledNames(){return mapper.findEnabledNames();}
}
