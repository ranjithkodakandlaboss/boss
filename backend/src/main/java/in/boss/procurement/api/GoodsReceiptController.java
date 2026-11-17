package in.boss.procurement.api;

import in.boss.procurement.domain.GoodsReceiptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static in.boss.procurement.api.ProcurementApiDtos.CreateGrnRequest;
import static in.boss.procurement.api.ProcurementApiDtos.GrnResponse;
import static in.boss.procurement.api.ProcurementApiDtos.PageResponse;

@RestController
@RequestMapping("/api/v1/grns")
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceiptService;

    public GoodsReceiptController(GoodsReceiptService goodsReceiptService) {
        this.goodsReceiptService = goodsReceiptService;
    }

    @GetMapping
    public PageResponse<GrnResponse> list(
            @RequestParam(required = false) UUID spoId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return goodsReceiptService.list(spoId, page, size);
    }

    @GetMapping("/{grnId}")
    public GrnResponse get(@PathVariable UUID grnId) {
        return goodsReceiptService.get(grnId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrnResponse receive(@Valid @RequestBody CreateGrnRequest request) {
        return goodsReceiptService.receive(request);
    }
}
