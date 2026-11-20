package in.boss.procurement.api;

import in.boss.procurement.domain.SpoAmendmentFileService;
import in.boss.procurement.domain.SpoService;
import in.boss.shared.security.BossPermissions;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.AmendSpoRequest;
import static in.boss.procurement.api.ProcurementApiDtos.AmendmentFileResponse;
import static in.boss.procurement.api.ProcurementApiDtos.CreateSpoRequest;
import static in.boss.procurement.api.ProcurementApiDtos.DeclineSpoRequest;
import static in.boss.procurement.api.ProcurementApiDtos.IssueSpoRequest;
import static in.boss.procurement.api.ProcurementApiDtos.PageResponse;
import static in.boss.procurement.api.ProcurementApiDtos.SpoAmendmentResponse;
import static in.boss.procurement.api.ProcurementApiDtos.SpoResponse;

@RestController
@RequestMapping("/api/v1/spos")
@PreAuthorize(BossPermissions.PROCUREMENT_MANAGE)
public class SpoController {

    private final SpoService spoService;
    private final SpoAmendmentFileService amendmentFileService;

    public SpoController(SpoService spoService, SpoAmendmentFileService amendmentFileService) {
        this.spoService = spoService;
        this.amendmentFileService = amendmentFileService;
    }

    @GetMapping
    public PageResponse<SpoResponse> list(
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return spoService.listSpos(supplierId, status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpoResponse create(@Valid @RequestBody CreateSpoRequest request) {
        return spoService.createSpo(request);
    }

    @GetMapping("/{spoId}")
    public SpoResponse get(@PathVariable UUID spoId) {
        return spoService.getSpo(spoId);
    }

    @PostMapping("/{spoId}/issue")
    public SpoResponse issue(
            @PathVariable UUID spoId,
            @Valid @RequestBody(required = false) IssueSpoRequest request) {
        return spoService.issueSpo(spoId, request);
    }

    @PostMapping("/{spoId}/acknowledge")
    public SpoResponse acknowledge(@PathVariable UUID spoId) {
        return spoService.acknowledgeSpo(spoId);
    }

    @PostMapping("/{spoId}/cancel")
    public SpoResponse cancel(@PathVariable UUID spoId) {
        return spoService.cancelSpo(spoId);
    }

    @PostMapping("/{spoId}/decline")
    public SpoResponse decline(@PathVariable UUID spoId, @Valid @RequestBody DeclineSpoRequest request) {
        return spoService.declineSpo(spoId, request);
    }

    @GetMapping("/{spoId}/amendments")
    public List<SpoAmendmentResponse> amendments(@PathVariable UUID spoId) {
        return spoService.listAmendments(spoId);
    }

    @PostMapping("/{spoId}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    public SpoAmendmentResponse amend(@PathVariable UUID spoId, @Valid @RequestBody AmendSpoRequest request) {
        return spoService.submitAmendment(spoId, request);
    }

    @PostMapping("/{spoId}/amendments/{amendmentId}/approve")
    public SpoResponse approveAmendment(@PathVariable UUID spoId, @PathVariable UUID amendmentId) {
        return spoService.decideAmendment(spoId, amendmentId, true);
    }

    @PostMapping("/{spoId}/amendments/{amendmentId}/reject")
    public SpoResponse rejectAmendment(@PathVariable UUID spoId, @PathVariable UUID amendmentId) {
        return spoService.decideAmendment(spoId, amendmentId, false);
    }

    @GetMapping("/{spoId}/amendments/{amendmentId}/files")
    public List<AmendmentFileResponse> files(@PathVariable UUID spoId, @PathVariable UUID amendmentId) {
        return amendmentFileService.list(spoId, amendmentId);
    }

    @PostMapping(value = "/{spoId}/amendments/{amendmentId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AmendmentFileResponse upload(@PathVariable UUID spoId,
                                        @PathVariable UUID amendmentId,
                                        @RequestParam("file") MultipartFile file) {
        return amendmentFileService.upload(spoId, amendmentId, file);
    }

    @GetMapping("/{spoId}/amendments/{amendmentId}/files/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID spoId,
                                           @PathVariable UUID amendmentId,
                                           @PathVariable UUID fileId) {
        SpoAmendmentFileService.StoredFile stored = amendmentFileService.download(spoId, amendmentId, fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(stored.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .body(stored.content());
    }
}
