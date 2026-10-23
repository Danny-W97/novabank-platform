package org.demo.shopapp.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest(properties={"shop.orders.reconciliation-enabled=false","shop.rate-limit.enabled=false","app.metrics-access.username=k4-metrics","app.metrics-access.password=k4-metrics-password"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(K4SecurityPolicyIT.BoundaryOnly.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners=K4SecurityPolicyIT.ContainerCleanup.class,mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class K4SecurityPolicyIT {

    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    
    static{POSTGRES.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        
    }
    public static class ContainerCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        // afterTestClass callbacks run in reverse order: Spring closes the dirty context first.
        @Override public int getOrder(){return Integer.MIN_VALUE;}
        @Override public void afterTestClass(org.springframework.test.context.TestContext context){POSTGRES.stop();}
    }

    @Autowired MockMvc mvc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    final ObjectMapper json=new ObjectMapper();
    final List<Map<String,Object>> evidence=new ArrayList<>();
    final String app="shop";
    Path root(){var cwd=Path.of("").toAbsolutePath();return Files.exists(cwd.resolve("scripts/ci/security-policy.json"))?cwd:cwd.getParent();}
    List<Map<String,Object>> policy()throws Exception{
        var tree=json.readTree(root().resolve("scripts/ci/security-policy.json").toFile());
        var rows=new ArrayList<Map<String,Object>>();
        for(var group:tree.get(app))for(var endpoint:group.get("endpoints")){
            var row=new LinkedHashMap<String,Object>();group.fields().forEachRemaining(field->{if(!field.getKey().equals("endpoints"))row.put(field.getKey(),field.getValue().asText());});
            row.put("endpoint",endpoint.asText());rows.add(row);
        }
        return rows;
    }
    @Test void explicitPoliciesMatchEveryControllerAndEnforceAccessAtTheRealFilterBoundary()throws Exception{
        var rows=policy();var expected=new TreeSet<String>();
        for(var row:rows)if(row.get("source").equals("mvc"))assertThat(expected.add((String)row.get("endpoint"))).as("Duplicate policy %s",row).isTrue();
        var actual=new TreeSet<String>();
        for(var entry:mappings.getHandlerMethods().entrySet()){
            if(!entry.getValue().getBeanType().getPackageName().startsWith("org.demo."))continue;
            assertThat(entry.getKey().getMethodsCondition().getMethods())
                    .as("K4 requires explicit HTTP methods: %s",entry.getValue()).isNotEmpty();
            for(var path:entry.getKey().getPatternValues())for(var method:entry.getKey().getMethodsCondition().getMethods())actual.add(method.name()+" "+path);
        }
        assertThat(actual).as("New/deleted controller routes require an explicit K4 policy").isEqualTo(expected);
        try{
            for(var row:rows)check(row);
            var report=new LinkedHashMap<String,Object>();report.put("verdict","PASS");report.put("layer","REAL_SECURITY_FILTERS_WITH_TEST_ONLY_CONTROLLER_INTERCEPTOR");
            report.put("application",app);report.put("registeredControllerOperations",actual.size());report.put("policyOperations",rows.size());
            report.put("checks",evidence);report.put("businessOwnershipProof","Separate real-controller/domain tests are mandatory in the K4 runner.");
            var target=Path.of("target/security/k4-"+app+"-boundary.json");Files.createDirectories(target.getParent());json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(),report);
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    void check(Map<String,Object> row)throws Exception{
        String endpoint=(String)row.get("endpoint"),access=(String)row.get("access");
        if(row.get("source").equals("filter")){filterEndpoint(endpoint,access);return;}
        if(access.equals("key")){keyEndpoint(row);return;}
        if(access.equals("metrics")){
            denied(endpoint,"ANONYMOUS",true,"metrics-no-credentials");
            var request=request(endpoint,true).header("Authorization","Basic "+Base64.getEncoder().encodeToString("k4-metrics:k4-metrics-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            allowed(endpoint,"METRICS_BASIC",mvc.perform(request).andReturn(),"metrics-credentials");
            deniedResult(endpoint,"METRICS_BASIC",mvc.perform(request(endpoint,true).header("Authorization","Basic "+Base64.getEncoder().encodeToString("k4-metrics:wrong".getBytes()))).andReturn(),"metrics-wrong-password");
            return;
        }
        for(var role:List.of("ANONYMOUS","USER","CUSTOMER","ADMIN","METRICS","UNKNOWN")){
            boolean permit=Set.of("public","callback","webhook","docs").contains(access) ||
                    access.equals("client")&&(role.equals(app.equals("bank")?"USER":"CUSTOMER")||role.equals("ADMIN")) ||
                    access.equals("admin")&&role.equals("ADMIN");
            var result=mvc.perform(subject(request(endpoint,true),role)).andReturn();
            if(permit)allowed(endpoint,role,result,"role");else deniedResult(endpoint,role,result,"role");
        }
        if(Set.of("client","admin").contains(access)){
            var pending=request(endpoint,true).sessionAttr("BANK_TOTP_LOGIN_CHALLENGE_ID","synthetic-pending");
            deniedResult(endpoint,"PRE_MFA",mvc.perform(pending).andReturn(),"unfinished-login");
            var disabled=org.springframework.security.core.userdetails.User.withUsername("k4@example.invalid").password("unused").roles("ADMIN").disabled(true).build();
            deniedResult(endpoint,"DISABLED",mvc.perform(request(endpoint,true).with(user(disabled))).andReturn(),"disabled-principal");
            var locked=org.springframework.security.core.userdetails.User.withUsername("k4@example.invalid").password("unused").roles("ADMIN").accountLocked(true).build();
            deniedResult(endpoint,"LOCKED",mvc.perform(request(endpoint,true).with(user(locked))).andReturn(),"locked-principal");
        }
        boolean unsafe=!endpoint.startsWith("GET ");
        if(unsafe&&!access.equals("webhook")){
            String role=access.equals("admin")?"ADMIN":app.equals("bank")?"USER":"CUSTOMER";
            deniedResult(endpoint,role,mvc.perform(subject(request(endpoint,false),role)).andReturn(),"missing-csrf");
            deniedResult(endpoint,role,mvc.perform(subject(request(endpoint,false),role).with(csrf().useInvalidToken())).andReturn(),"invalid-csrf");
        }
        if(access.equals("docs")){
            var profiles=environment.getActiveProfiles();
            try{environment.setActiveProfiles("test","prod");for(var role:List.of("ANONYMOUS","USER","CUSTOMER","ADMIN","METRICS")){
                var result=mvc.perform(subject(request(endpoint,true),role)).andReturn();
                if(role.equals("ADMIN"))allowed(endpoint,role,result,"prod-docs");else deniedResult(endpoint,role,result,"prod-docs");
            }}finally{environment.setActiveProfiles(profiles);}
        }
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String endpoint,boolean token){
        var pieces=endpoint.split(" ",2);var uri=pieces[1].replaceAll("\\{[^}]+}","1");
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(pieces[0]),uri).contentType("application/json").content("{}");
        request.with(r->{r.setRemoteAddr("192.0.2."+((evidence.size()%240)+1));return r;});
        return token?request.with(csrf()):request;
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder subject(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String role){
        return role.equals("ANONYMOUS")?request:request.with(user("k4@example.invalid").roles(role));
    }
    void denied(String endpoint,String role,boolean token,String scenario)throws Exception{deniedResult(endpoint,role,mvc.perform(subject(request(endpoint,token),role)).andReturn(),scenario);}
    void allowed(String endpoint,String role,MvcResult result,String scenario){
        assertThat(result.getResponse().getHeader("X-K4-Boundary")).as("%s %s %s status=%s",endpoint,role,scenario,result.getResponse().getStatus()).isEqualTo("REACHED");
        assertThat(result.getResponse().getStatus()).isEqualTo(204);record(endpoint,role,scenario,"ALLOW");
    }
    void deniedResult(String endpoint,String role,MvcResult result,String scenario){
        assertThat(result.getResponse().getHeader("X-K4-Boundary")).as("%s %s %s",endpoint,role,scenario).isNull();
        assertThat(result.getResponse().getStatus()).as("%s %s %s",endpoint,role,scenario).isIn(302,401,403);record(endpoint,role,scenario,"DENY");
    }
    void record(String endpoint,String role,String scenario,String result){evidence.add(Map.of("endpoint",endpoint,"subject",role,"scenario",scenario,"result",result));}
    void filterEndpoint(String endpoint,String access)throws Exception{
        deniedResult(endpoint,"ANONYMOUS",mvc.perform(request(endpoint,false)).andReturn(),"filter-missing-csrf");
        deniedResult(endpoint,"ANONYMOUS",mvc.perform(request(endpoint,false).with(csrf().useInvalidToken())).andReturn(),"filter-invalid-csrf");
        var result=mvc.perform(subject(request(endpoint,true),"ADMIN")).andReturn();
        assertThat(result.getResponse().getHeader("X-K4-Boundary")).isNull();
        if(access.equals("logout")){
            assertThat(result.getResponse().getStatus()).as(endpoint).isIn(204,302);
            var session=result.getRequest().getSession(false);
            assertThat(session==null || session.getAttribute("SPRING_SECURITY_CONTEXT")==null ||
                ((org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication()==null).isTrue();
        }else assertThat(result.getResponse().getStatus()).as(endpoint).isEqualTo(302);
        record(endpoint,"ADMIN",access.equals("logout")?"filter-logout":"filter-invalid-credentials","HANDLED_BY_SECURITY_FILTER");
    }
    void keyEndpoint(Map<String,Object> row){throw new AssertionError("No key API in shop policy");}
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class BoundaryOnly{
        @org.springframework.context.annotation.Bean org.springframework.web.servlet.handler.MappedInterceptor k4Boundary(){
            return new org.springframework.web.servlet.handler.MappedInterceptor(new String[]{"/**"},new org.springframework.web.servlet.HandlerInterceptor(){
                @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler){
                    response.setHeader("X-K4-Boundary","REACHED");response.setStatus(204);return false;
                }
            });
        }
    }

}

