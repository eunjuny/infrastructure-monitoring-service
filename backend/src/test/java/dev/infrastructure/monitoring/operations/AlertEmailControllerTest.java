package dev.infrastructure.monitoring.operations;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlertEmailControllerTest {
    @Test void retainsValidationStatusInsteadOfDispatchingToForbiddenErrorPage() throws Exception {
        var service = mock(AlertEmailService.class);
        when(service.update(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "private diagnostic"));
        MockMvcBuilders.standaloneSetup(new AlertEmailController(service)).build()
                .perform(put("/api/operations/alert-email").contentType("application/json")
                        .content("{\"recipient\":\"admin@example.invalid\",\"enabled\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("수신 주소와 SMTP 설정을 확인해 주세요."));
    }
}
