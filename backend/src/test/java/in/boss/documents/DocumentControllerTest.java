package in.boss.documents;

import in.boss.documents.api.DocumentDtos.ArchiveDocumentRequest;
import in.boss.documents.api.DocumentDtos.CreateDocumentRequest;
import in.boss.documents.api.DocumentDtos.DocumentResponse;
import in.boss.documents.domain.DocumentVaultService;
import in.boss.documents.storage.InMemoryStorageService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DocumentControllerTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditService auditService;

    @Autowired
    private DocumentVaultService documentService;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("doc-ctrl-" + UUID.randomUUID().toString().substring(0, 8));
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, tenant_id, firebase_uid, email, display_name, status) VALUES (?, ?, ?, 'owner@test.com', 'Owner User', 'active')", userId, tenantId, "uid-" + userId);
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("ROLE_OWNER"), null, "firebase-uid"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("POST /api/v1/documents uploads file and returns 201")
    void uploadDocument_endpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "drawing.pdf", "application/pdf", "drawing bytes".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .param("title", "Machine Layout Drawing")
                        .param("category", "Asset Documents")
                        .param("docType", "Technical Drawing")
                        .param("issueDate", "2025-01-15")
                        .param("expiryDate", "2026-01-15")
                        .param("reminderDays", "30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Machine Layout Drawing"))
                .andExpect(jsonPath("$.category").value("Asset Documents"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @ParameterizedTest
    @CsvSource({
            "Sales Documents, RFQ",
            "Sales Documents, CustomerPO",
            "Customer Documents, Customer"
    })
    @WithMockUser(roles = "SALES_MANAGER")
    @DisplayName("POST /api/v1/documents allows sales managers to attach linked sales and customer documents")
    void salesManagerCanUploadLinkedSalesDocument(String category, String linkedEntityType) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID customerId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customers (tenant_id, code, name) VALUES (?, ?, ?) RETURNING id::text",
                String.class, tenantId, "DOC-CUST-" + suffix, "Sales Customer"));
        String linkedEntityRef = linkedEntityType.equals("Customer") ? "Sales Customer" : "DOC-" + suffix;
        UUID linkedEntityId = customerId;
        if (!"Customer".equals(linkedEntityType)) {
            UUID legalEntityId = UUID.fromString(jdbc.queryForObject(
                    "INSERT INTO legal_entities (tenant_id, name, legal_name) VALUES (?, ?, ?) RETURNING id::text",
                    String.class, tenantId, "Sales Entity " + suffix, "Sales Entity Legal " + suffix));
            UUID siteId = UUID.fromString(jdbc.queryForObject(
                    "INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code) VALUES (?, ?, ?, ?) RETURNING id::text",
                    String.class, tenantId, legalEntityId, "Sales Site " + suffix, "DOC-SITE-" + suffix));
            if ("RFQ".equals(linkedEntityType)) {
                linkedEntityId = UUID.fromString(jdbc.queryForObject(
                        "INSERT INTO rfqs (tenant_id, rfq_number, customer_id, site_id, received_date, created_by) "
                                + "VALUES (?, ?, ?, ?, CURRENT_DATE, ?) RETURNING id::text",
                        String.class, tenantId, linkedEntityRef, customerId, siteId, userId));
            } else {
                linkedEntityId = UUID.fromString(jdbc.queryForObject(
                        "INSERT INTO cpos (tenant_id, cpo_number, customer_id, site_id, status, order_date, created_by) "
                                + "VALUES (?, ?, ?, ?, 'open', CURRENT_DATE, ?) RETURNING id::text",
                        String.class, tenantId, linkedEntityRef, customerId, siteId, userId));
            }
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales-document.pdf", "application/pdf", "sales evidence".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .param("title", "Sales document")
                        .param("category", category)
                        .param("docType", linkedEntityType + " Document")
                        .param("linkedEntityType", linkedEntityType)
                        .param("linkedEntityId", linkedEntityId.toString())
                        .param("linkedEntityRef", linkedEntityRef))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sales document"))
                .andExpect(jsonPath("$.category").value(category))
                .andExpect(jsonPath("$.linkedEntities[0].id").value(linkedEntityId.toString()));
    }

    @Test
    @WithMockUser(roles = "SALES_MANAGER")
    @DisplayName("Sales managers can upload documents but cannot renew vault documents")
    void salesManagerCannotRenewDocuments() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales-document.pdf", "application/pdf", "sales evidence".getBytes(StandardCharsets.UTF_8));
        DocumentResponse document = documentService.uploadDocument(new CreateDocumentRequest(
                "Sales document", "Sales Documents", "Sales evidence", null, null, 30, null, null, null, null), file);

        mockMvc.perform(multipart("/api/v1/documents/{id}/renew", document.id())
                        .file(new MockMultipartFile(
                                "file", "renewed.pdf", "application/pdf", "new evidence".getBytes(StandardCharsets.UTF_8)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .param("expiryDate", LocalDate.now().plusYears(1).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("GET /api/v1/documents lists documents and filters by category")
    void listDocuments_endpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cert.pdf", "application/pdf", "cert bytes".getBytes(StandardCharsets.UTF_8));

        documentService.uploadDocument(new CreateDocumentRequest(
                "Supplier ISO", "Supplier Documents", "Cert", null, null, 30, null, null, null, null), file);

        mockMvc.perform(get("/api/v1/documents")
                        .param("category", "Supplier Documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].title").value("Supplier ISO"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("GET /api/v1/documents/{id} returns document details")
    void getDocument_endpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "test".getBytes(StandardCharsets.UTF_8));

        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Specific Doc", "Quality Documents", "Test", null, null, 30, null, null, null, null), file);

        mockMvc.perform(get("/api/v1/documents/{id}", doc.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doc.id().toString()))
                .andExpect(jsonPath("$.title").value("Specific Doc"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("POST /api/v1/documents/{id}/renew renews document and increments version")
    void renewDocument_endpoint() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("file", "v1.pdf", "application/pdf", "v1".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Renewable Cert", "Company Certificates", "Cert", null, LocalDate.now().plusMonths(1), 30, null, null, null, null), file1);

        MockMultipartFile file2 = new MockMultipartFile("file", "v2.pdf", "application/pdf", "v2".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/documents/{id}/renew", doc.id())
                        .file(file2)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .param("expiryDate", LocalDate.now().plusYears(1).toString())
                        .param("reminderDays", "60")
                        .param("notes", "Renewed for year 2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.fileName").value("v2.pdf"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("PATCH /api/v1/documents/{id}/archive archives document")
    void archiveDocument_endpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "v1.pdf", "application/pdf", "v1".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "To Archive", "Job Documents", "General", null, null, 30, null, null, null, null), file);

        mockMvc.perform(patch("/api/v1/documents/{id}/archive", doc.id())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Job completed and closed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"))
                .andExpect(jsonPath("$.archiveReason").value("Job completed and closed"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("GET /api/v1/documents/{id}/download serves file content with appropriate headers")
    void downloadDocument_endpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice_spec.pdf", "application/pdf", "file content 12345".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Downloadable Doc", "Quality Documents", "PDF", null, null, 30, null, null, null, null), file);

        mockMvc.perform(get("/api/v1/documents/{id}/download", doc.id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "form-data; name=\"inline\"; filename=\"invoice_spec.pdf\""))
                .andExpect(content().bytes("file content 12345".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    @DisplayName("GET /api/v1/documents/{id}/history returns audit history")
    void getHistory_endpoint() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "test".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "History Doc", "Asset Documents", "Drawing", null, null, 30, null, null, null, null), file);

        mockMvc.perform(get("/api/v1/documents/{id}/history", doc.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].action").value("DOCUMENT_UPLOADED"));
    }
}
