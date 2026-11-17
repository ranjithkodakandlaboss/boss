package in.boss.procurement;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProcurementContractValidationTest {

    @Test
    void supplierQuotationAndSpoLifecycleAndMandatoryQuotationLinkMatchApprovedContract() throws IOException {
        Path contractPath = findRepositoryRoot().resolve(
                "BOSS_PRD_AND_BACKLOG_v1.1.3/10_API_CONTRACTS/openapi/procurement.yaml");
        Map<?, ?> contract = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(contractPath));
        Map<?, ?> schemas = map(map(contract.get("components")).get("schemas"));
        Map<?, ?> quote = map(schemas.get("SupplierQuotation"));
        Map<?, ?> spo = map(schemas.get("SPO"));

        assertThat(enumValues(quote, "status"))
                .containsExactly("requested", "received", "under_review", "selected", "rejected");
        assertThat(enumValues(spo, "status"))
                .containsExactly("draft", "issued", "acknowledged", "partially_received", "fully_received", "cancelled");

        Map<?, ?> spoPost = map(map(contract.get("paths")).get("/spos"));
        Map<?, ?> requestSchema = map(map(map(map(map(spoPost.get("post")).get("requestBody"))
                .get("content")).get("application/json")).get("schema"));
        List<String> required = strings(requestSchema.get("required"));
        Map<?, ?> properties = map(requestSchema.get("properties"));
        Map<?, ?> quoteReference = map(properties.get("supplierQuotationId"));

        assertThat(required).contains("supplierId", "prId", "supplierQuotationId", "lines");
        assertThat(quoteReference.containsKey("nullable")).isFalse();

        Map<?, ?> paths = map(contract.get("paths"));
        assertThat(paths.keySet().stream().map(String.class::cast).toList()).containsAll(Set.of(
                "/prs/{prId}/supplier-quotations",
                "/prs/{prId}/supplier-quotations/comparison",
                "/supplier-quotations/{quotationId}",
                "/supplier-quotations/{quotationId}/receive",
                "/supplier-quotations/{quotationId}/review",
                "/supplier-quotations/{quotationId}/select",
                "/supplier-quotations/{quotationId}/reject",
                "/spos/{spoId}/issue",
                "/spos/{spoId}/acknowledge",
                "/spos/{spoId}/cancel"));

        assertMutationRequiresIdempotencyKey(paths, "/prs/{prId}/supplier-quotations");
        assertMutationRequiresIdempotencyKey(paths, "/supplier-quotations/{quotationId}/receive");
        assertMutationRequiresIdempotencyKey(paths, "/supplier-quotations/{quotationId}/review");
        assertMutationRequiresIdempotencyKey(paths, "/supplier-quotations/{quotationId}/select");
        assertMutationRequiresIdempotencyKey(paths, "/supplier-quotations/{quotationId}/reject");
        assertMutationRequiresIdempotencyKey(paths, "/spos");
        assertMutationRequiresIdempotencyKey(paths, "/spos/{spoId}/issue");
        assertMutationRequiresIdempotencyKey(paths, "/spos/{spoId}/acknowledge");
        assertMutationRequiresIdempotencyKey(paths, "/spos/{spoId}/cancel");
    }

    private static void assertMutationRequiresIdempotencyKey(Map<?, ?> paths, String path) {
        Map<?, ?> pathItem = map(paths.get(path));
        Map<?, ?> operation = map(pathItem.get("post"));
        List<?> parameters = (List<?>) operation.get("parameters");
        boolean required = parameters.stream()
                .map(ProcurementContractValidationTest::map)
                .anyMatch(parameter -> "Idempotency-Key".equals(parameter.get("name"))
                        && Boolean.TRUE.equals(parameter.get("required")));
        assertThat(required).isTrue();
    }

    private static List<String> enumValues(Map<?, ?> schema, String propertyName) {
        Map<?, ?> properties = map(schema.get("properties"));
        Map<?, ?> property = map(properties.get(propertyName));
        return strings(property.get("enum"));
    }

    private static List<String> strings(Object value) {
        return ((List<?>) value).stream().map(String.class::cast).toList();
    }

    private static Map<?, ?> map(Object value) {
        return (Map<?, ?>) value;
    }

    private static Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null
                && !Files.isDirectory(directory.resolve("BOSS_PRD_AND_BACKLOG_v1.1.3"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate repository root from the test working directory");
        }
        return directory;
    }
}
