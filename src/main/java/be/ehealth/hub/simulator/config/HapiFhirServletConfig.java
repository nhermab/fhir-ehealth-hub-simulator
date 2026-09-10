package be.ehealth.hub.simulator.config;

import be.ehealth.hub.simulator.service.DocumentRepository;
import be.ehealth.hub.simulator.web.InterhubGatewayFilter;
import ca.uhn.fhir.context.FhirContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class HapiFhirServletConfig {

    /**
     * One shared R4 context for the whole application: building a {@link FhirContext} is
     * expensive and it is thread-safe once configured.
     */
    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }

    @Bean
    public ServletRegistrationBean<HubRestfulServer> fhirServletRegistration(HubRestfulServer server) {
        ServletRegistrationBean<HubRestfulServer> registration = new ServletRegistrationBean<>(server, "/fhir/*");
        registration.setName("HubFhirServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<InterhubGatewayFilter> interhubGatewayFilter(DocumentRepository repository,
                                                                              FhirContext fhirContext) {
        FilterRegistrationBean<InterhubGatewayFilter> bean =
                new FilterRegistrationBean<>(new InterhubGatewayFilter(repository, fhirContext));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
