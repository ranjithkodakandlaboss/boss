package in.boss.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigControllerContractTest {

    @Test
    void categoriesAreAvailableAtTheReferenceDataEndpoint() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getCategories()).thenReturn(List.of(
                new ConfigCategoryDto("PAYMENT_MODES", "Payment modes", "tenant", true, true)));

        var mvc = MockMvcBuilders.standaloneSetup(new ConfigController(service)).build();
        mvc.perform(get("/api/v1/config/options/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PAYMENT_MODES"));
    }
}
