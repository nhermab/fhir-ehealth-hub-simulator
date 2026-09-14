package be.ehealth.hub.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.assertThat;

class ViewerCorsTest extends AbstractSimulatorTest {
    static {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    @Test
    void hostedViewerCanPreflightRootSearch() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://dev.ehealthhub.be");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(java.util.List.of("content-type", "x-simulate-partial-failure"));
        ResponseEntity<String> response = restTemplate.exchange(url("/DocumentReference/_search"),
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://dev.ehealthhub.be");
    }

    @Test
    void unrelatedOriginIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://unrelated.example");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        ResponseEntity<String> response = restTemplate.exchange(url("/DocumentReference/_search"),
                HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
