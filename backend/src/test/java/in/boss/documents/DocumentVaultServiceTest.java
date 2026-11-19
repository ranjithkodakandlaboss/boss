package in.boss.documents;

import in.boss.documents.api.DocumentDtos.ArchiveDocumentRequest;
import in.boss.documents.api.DocumentDtos.CreateDocumentRequest;
import in.boss.documents.api.DocumentDtos.DocumentHistoryEntry;
import in.boss.documents.api.DocumentDtos.DocumentResponse;
import in.boss.documents.api.DocumentDtos.PageResponse;
import in.boss.documents.api.DocumentDtos.RenewDocumentRequest;
import in.boss.documents.domain.DocumentNotFoundException;
import in.boss.documents.domain.DocumentLinkValidator;
import in.boss.documents.domain.DocumentValidationException;
import in.boss.documents.domain.DocumentVaultService;
import in.boss.documents.domain.DocumentVaultService.StoredDocumentFile;
import in.boss.documents.storage.InMemoryStorageService;
import in.boss.masterdata.BaseMasterDataIntegrationTest;
import in.boss.shared.audit.AuditService;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentVaultServiceTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private AuditService auditService;

    private InMemoryStorageService storageService;
    private DocumentVaultService documentService;

    private UUID tenantA;
    private UUID tenantB;
    private UUID userA;
    private UUID userB;
    private UUID supplierId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        storageService = new InMemoryStorageService();
        documentService = new DocumentVaultService(jdbc, storageService, auditService, new DocumentLinkValidator(jdbc));

        tenantA = provisionTenant("doc-vault-a-" + UUID.randomUUID().toString().substring(0, 8));
        tenantB = provisionTenant("doc-vault-b-" + UUID.randomUUID().toString().substring(0, 8));

        userA = UUID.randomUUID();
        userB = UUID.randomUUID();

        // Insert test app users
        jdbc.update("INSERT INTO app_users (id, tenant_id, firebase_uid, email, display_name, status) VALUES (?, ?, ?, 'alice@test.com', 'Alice Smith', 'active')", userA, tenantA, "uid-a-" + userA);
        jdbc.update("INSERT INTO app_users (id, tenant_id, firebase_uid, email, display_name, status) VALUES (?, ?, ?, 'bob@test.com', 'Bob Jones', 'active')", userB, tenantB, "uid-b-" + userB);

        // Insert legal entity and test records for tenantA
        UUID leA = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO legal_entities (tenant_id, name, legal_name) VALUES (?, 'Tenant A Entity', 'Tenant A Legal') RETURNING id::text",
                String.class, tenantA));

        supplierId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO suppliers (tenant_id, code, name, legal_name) VALUES (?, 'SUP-001', 'Apex Metals', 'Apex Metals Pvt Ltd') RETURNING id::text",
                String.class, tenantA));

        customerId = UUID.fromString(jdbc.queryForObject(
                "INSERT INTO customers (tenant_id, code, name, legal_name) VALUES (?, 'CUST-001', 'Beta Corp', 'Beta Corp Ltd') RETURNING id::text",
                String.class, tenantA));

        // Default to tenantA context
        setContext(tenantA, userA);
    }

    private void setContext(UUID tenantId, UUID userId) {
        BossContextHolder.set(new BossContext(tenantId, userId, Set.of("ROLE_OWNER"), null, "firebase-uid"));
    }

    @Test
    @DisplayName("Upload document succeeds and creates version 1")
    void uploadDocument_success() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "iso_9001.pdf", "application/pdf", "dummy pdf content".getBytes(StandardCharsets.UTF_8));

        CreateDocumentRequest request = new CreateDocumentRequest(
                "ISO 9001 Certificate",
                "Company Certificates",
                "Quality Certificate",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 1, 1),
                30,
                "verified",
                "Supplier",
                supplierId,
                "Apex Metals"
        );

        DocumentResponse doc = documentService.uploadDocument(request, file);

        assertThat(doc.id()).isNotNull();
        assertThat(doc.title()).isEqualTo("ISO 9001 Certificate");
        assertThat(doc.category()).isEqualTo("Company Certificates");
        assertThat(doc.docType()).isEqualTo("Quality Certificate");
        assertThat(doc.status()).isEqualTo("active");
        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.fileName()).isEqualTo("iso_9001.pdf");
        assertThat(doc.certStatus()).isEqualTo("verified");
        assertThat(doc.linkedEntities()).hasSize(1);
        assertThat(doc.linkedEntities().get(0).type()).isEqualTo("Supplier");
        assertThat(doc.linkedEntities().get(0).ref()).isEqualTo("Apex Metals");

        // Verify file stored in storage service
        StoredDocumentFile stored = documentService.downloadDocument(doc.id(), null);
        assertThat(stored.fileName()).isEqualTo("iso_9001.pdf");
        assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo("dummy pdf content");
    }

    @Test
    @DisplayName("Upload document fails validation when title or file is missing")
    void uploadDocument_validations() {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "content".getBytes(StandardCharsets.UTF_8));

        // Missing title
        assertThatThrownBy(() -> documentService.uploadDocument(
                new CreateDocumentRequest("", "Job Documents", "General", null, null, 30, null, null, null, null), file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Title is required");

        // Invalid category
        assertThatThrownBy(() -> documentService.uploadDocument(
                new CreateDocumentRequest("Title", "InvalidCategory", "General", null, null, 30, null, null, null, null), file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Invalid category");

        // Expiry before issue date
        assertThatThrownBy(() -> documentService.uploadDocument(
                new CreateDocumentRequest("Title", "Job Documents", "General",
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), 30, null, null, null, null), file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Expiry date cannot be earlier than issue date");

        // Invalid reminder days
        assertThatThrownBy(() -> documentService.uploadDocument(
                new CreateDocumentRequest("Title", "Job Documents", "General",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), 500, null, null, null, null), file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Reminder days must be between 1 and 365");

        // Null file
        assertThatThrownBy(() -> documentService.uploadDocument(
                new CreateDocumentRequest("Title", "Job Documents", "General", null, null, 30, null, null, null, null), null))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Please select a file to upload");
    }

    @Test
    @DisplayName("Upload with invalid or cross-tenant linked entity is rejected")
    void uploadDocument_invalidLinkedEntity() {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "content".getBytes(StandardCharsets.UTF_8));

        UUID randomId = UUID.randomUUID();
        CreateDocumentRequest request = new CreateDocumentRequest(
                "Drawing", "Job Documents", "Drawing", null, null, 30, null, "Supplier", randomId, "Nonexistent");

        assertThatThrownBy(() -> documentService.uploadDocument(request, file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Linked Supplier record not found for this tenant");
    }

    @Test
    @DisplayName("Renew document creates version 2 and updates expiry")
    void renewDocument_success() {
        MockMultipartFile file1 = new MockMultipartFile("file", "cert_v1.pdf", "application/pdf", "v1 content".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Safety Cert", "Quality Documents", "Cert", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), 30, null, null, null, null), file1);

        MockMultipartFile file2 = new MockMultipartFile("file", "cert_v2.pdf", "application/pdf", "v2 content".getBytes(StandardCharsets.UTF_8));
        RenewDocumentRequest renewReq = new RenewDocumentRequest(
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 1, 1),
                45,
                "Renewed by external audit"
        );

        DocumentResponse renewed = documentService.renewDocument(doc.id(), renewReq, file2);

        assertThat(renewed.version()).isEqualTo(2);
        assertThat(renewed.expiryDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(renewed.reminderDays()).isEqualTo(45);
        assertThat(renewed.fileName()).isEqualTo("cert_v2.pdf");

        // Verify both versions exist and are retrievable
        StoredDocumentFile v1 = documentService.downloadDocument(doc.id(), 1);
        assertThat(new String(v1.content(), StandardCharsets.UTF_8)).isEqualTo("v1 content");

        StoredDocumentFile v2 = documentService.downloadDocument(doc.id(), 2);
        assertThat(new String(v2.content(), StandardCharsets.UTF_8)).isEqualTo("v2 content");

        // Latest version download
        StoredDocumentFile latest = documentService.downloadDocument(doc.id(), null);
        assertThat(new String(latest.content(), StandardCharsets.UTF_8)).isEqualTo("v2 content");
    }

    @Test
    @DisplayName("Renew validation fails on missing file, missing expiry, or archived document")
    void renewDocument_validations() {
        MockMultipartFile file1 = new MockMultipartFile("file", "test.pdf", "application/pdf", "content".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Doc", "Job Documents", "General", null, LocalDate.now().plusDays(10), 30, null, null, null, null), file1);

        // Missing file
        assertThatThrownBy(() -> documentService.renewDocument(doc.id(),
                new RenewDocumentRequest(LocalDate.now(), LocalDate.now().plusYears(1), 30, null), null))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Please attach the renewed document file");

        // Missing expiry
        assertThatThrownBy(() -> documentService.renewDocument(doc.id(),
                new RenewDocumentRequest(LocalDate.now(), null, 30, null), file1))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("New expiry date is required");

        // Expiry before issue
        assertThatThrownBy(() -> documentService.renewDocument(doc.id(),
                new RenewDocumentRequest(LocalDate.now().plusDays(5), LocalDate.now().plusDays(2), 30, null), file1))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Expiry date cannot be earlier than issue date");

        // Archive first, then attempt renew
        documentService.archiveDocument(doc.id(), new ArchiveDocumentRequest("Contract expired"));
        assertThatThrownBy(() -> documentService.renewDocument(doc.id(),
                new RenewDocumentRequest(LocalDate.now(), LocalDate.now().plusYears(1), 30, null), file1))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Cannot renew an archived document");
    }

    @Test
    @DisplayName("Archive document sets status and records mandatory reason")
    void archiveDocument_success() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "data".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Drawing 101", "Asset Documents", "Drawing", null, null, 30, null, null, null, null), file);

        DocumentResponse archived = documentService.archiveDocument(doc.id(), new ArchiveDocumentRequest("Obsolete asset retired"));

        assertThat(archived.status()).isEqualTo("archived");
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(archived.archiveReason()).isEqualTo("Obsolete asset retired");

        // Empty reason throws
        assertThatThrownBy(() -> documentService.archiveDocument(doc.id(), new ArchiveDocumentRequest("   ")))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("Archive reason is required");
    }

    @Test
    @DisplayName("List documents filters by category, status, search, and attention")
    void listDocuments_filtering() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "data".getBytes(StandardCharsets.UTF_8));

        // Create 1 job doc, 1 quality doc (expiring), 1 supplier doc (expired)
        documentService.uploadDocument(new CreateDocumentRequest(
                "Mill Job Spec", "Job Documents", "Spec", null, LocalDate.now().plusDays(100), 30, null, null, null, null), file);

        documentService.uploadDocument(new CreateDocumentRequest(
                "Calibration Report", "Quality Documents", "Calibration", null, LocalDate.now().plusDays(15), 30, null, null, null, null), file);

        documentService.uploadDocument(new CreateDocumentRequest(
                "Vendor Agreement", "Supplier Documents", "Contract", null, LocalDate.now().minusDays(5), 30, null, null, null, null), file);

        // List all (default excludes archived)
        PageResponse<DocumentResponse> all = documentService.listDocuments(null, null, null, false, null, null, null, 0, 50);
        assertThat(all.content()).hasSize(3);

        // Filter category Job Documents
        PageResponse<DocumentResponse> jobDocs = documentService.listDocuments("Job Documents", null, null, false, null, null, null, 0, 50);
        assertThat(jobDocs.content()).hasSize(1);
        assertThat(jobDocs.content().get(0).title()).isEqualTo("Mill Job Spec");

        // Filter attention: expiring
        PageResponse<DocumentResponse> expiring = documentService.listDocuments(null, null, "expiring", false, null, null, null, 0, 50);
        assertThat(expiring.content()).hasSize(1);
        assertThat(expiring.content().get(0).title()).isEqualTo("Calibration Report");

        // Filter attention: expired
        PageResponse<DocumentResponse> expired = documentService.listDocuments(null, null, "expired", false, null, null, null, 0, 50);
        assertThat(expired.content()).hasSize(1);
        assertThat(expired.content().get(0).title()).isEqualTo("Vendor Agreement");

        // Filter attention: due (both expiring and expired)
        PageResponse<DocumentResponse> due = documentService.listDocuments(null, null, "due", false, null, null, null, 0, 50);
        assertThat(due.content()).hasSize(2);

        // Search text
        PageResponse<DocumentResponse> searched = documentService.listDocuments(null, null, null, false, "Calibration", null, null, 0, 50);
        assertThat(searched.content()).hasSize(1);
        assertThat(searched.content().get(0).title()).isEqualTo("Calibration Report");
    }

    @Test
    @DisplayName("Archived documents excluded by default but included when includeArchived=true")
    void listDocuments_archivedVisibility() {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "data".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Old Manual", "Asset Documents", "Manual", null, null, 30, null, null, null, null), file);

        documentService.archiveDocument(doc.id(), new ArchiveDocumentRequest("Decommissioned"));

        // Default list excludes archived
        PageResponse<DocumentResponse> defaultList = documentService.listDocuments(null, null, null, false, null, null, null, 0, 50);
        assertThat(defaultList.content()).isEmpty();

        // includeArchived=true includes it
        PageResponse<DocumentResponse> withArchived = documentService.listDocuments(null, null, null, true, null, null, null, 0, 50);
        assertThat(withArchived.content()).hasSize(1);
        assertThat(withArchived.content().get(0).id()).isEqualTo(doc.id());
    }

    @Test
    @DisplayName("Cross-tenant isolation: Tenant B cannot access Tenant A documents or files")
    void crossTenantIsolation() {
        MockMultipartFile file = new MockMultipartFile("file", "secret.pdf", "application/pdf", "tenant A secret".getBytes(StandardCharsets.UTF_8));
        DocumentResponse docA = documentService.uploadDocument(new CreateDocumentRequest(
                "Tenant A Secret", "Quality Documents", "Confidential", null, null, 30, null, null, null, null), file);

        // Switch context to Tenant B
        setContext(tenantB, userB);

        // Cannot get document
        assertThatThrownBy(() -> documentService.getDocument(docA.id()))
                .isInstanceOf(DocumentNotFoundException.class);

        // Cannot download file
        assertThatThrownBy(() -> documentService.downloadDocument(docA.id(), null))
                .isInstanceOf(DocumentNotFoundException.class);

        // Cannot renew
        assertThatThrownBy(() -> documentService.renewDocument(docA.id(),
                new RenewDocumentRequest(null, LocalDate.now().plusYears(1), 30, null), file))
                .isInstanceOf(DocumentNotFoundException.class);

        // Cannot archive
        assertThatThrownBy(() -> documentService.archiveDocument(docA.id(), new ArchiveDocumentRequest("Hacking")))
                .isInstanceOf(DocumentNotFoundException.class);

        // Tenant B's list does not show Tenant A's document
        PageResponse<DocumentResponse> listB = documentService.listDocuments(null, null, null, true, null, null, null, 0, 50);
        assertThat(listB.content()).isEmpty();
    }

    @Test
    @DisplayName("Audit events recorded for upload, renew, and archive")
    void auditEventsRecorded() {
        MockMultipartFile file1 = new MockMultipartFile("file", "v1.pdf", "application/pdf", "content".getBytes(StandardCharsets.UTF_8));
        DocumentResponse doc = documentService.uploadDocument(new CreateDocumentRequest(
                "Test Audit Doc", "Company Certificates", "Certificate", null, LocalDate.now().plusMonths(6), 30, null, null, null, null), file1);

        MockMultipartFile file2 = new MockMultipartFile("file", "v2.pdf", "application/pdf", "content2".getBytes(StandardCharsets.UTF_8));
        documentService.renewDocument(doc.id(), new RenewDocumentRequest(null, LocalDate.now().plusYears(1), 30, "Renewed"), file2);

        documentService.archiveDocument(doc.id(), new ArchiveDocumentRequest("Done"));

        List<DocumentHistoryEntry> history = documentService.getDocumentHistory(doc.id());
        assertThat(history).hasSize(3);
        assertThat(history).extracting(DocumentHistoryEntry::action)
                .containsExactly("DOCUMENT_ARCHIVED", "DOCUMENT_RENEWED", "DOCUMENT_UPLOADED");
    }

    @Test
    @DisplayName("File size formatting works for KB and MB")
    void formatFileSize() {
        assertThat(DocumentVaultService.formatFileSize(500)).isEqualTo("1 KB");
        assertThat(DocumentVaultService.formatFileSize(1024)).isEqualTo("1 KB");
        assertThat(DocumentVaultService.formatFileSize(2048)).isEqualTo("2 KB");
        assertThat(DocumentVaultService.formatFileSize(2500000)).isEqualTo("2.4 MB");
    }
}
