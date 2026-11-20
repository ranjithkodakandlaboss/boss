package in.boss.dispatch.domain;

import in.boss.dispatch.api.DispatchDtos.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

final class DispatchReader {

    private static final Map<String, String> HISTORY_TITLES = Map.of(
            "create", "Dispatch Created",
            "status_change", "Status Updated",
            "pod_recorded", "Proof of Delivery Confirmed",
            "packing_recorded", "Packing Recorded"
    );

    private final JdbcTemplate jdbc;

    DispatchReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DispatchSummary> listDispatches(UUID tenantId, String siteFilter, String statusFilter, String search) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.dispatch_number, d.customer_id, c.name AS customer_name,
                       d.cpo_id, cp.cpo_number, d.job_id, pj.job_number,
                       d.site_id, ps.name AS site_name, d.source_store_id, st.name AS store_name,
                       COALESCE(d.carrier, d.transporter) AS carrier,
                       d.dispatch_method_option_id, d.dispatch_method_label_snapshot AS dispatch_method_label,
                       d.vehicle_number, d.driver_name,
                       d.lr_number, d.dispatch_date, d.expected_delivery, d.total_qty, d.line_count,
                       d.status, d.has_pod, d.created_at
                FROM dispatches d
                JOIN customers c ON c.id = d.customer_id AND c.tenant_id = d.tenant_id
                JOIN physical_sites ps ON ps.id = d.site_id AND ps.tenant_id = d.tenant_id
                LEFT JOIN cpos cp ON cp.id = d.cpo_id AND cp.tenant_id = d.tenant_id
                LEFT JOIN production_jobs pj ON pj.id = d.job_id AND pj.tenant_id = d.tenant_id
                LEFT JOIN stores st ON st.id = d.source_store_id AND st.tenant_id = d.tenant_id
                WHERE d.tenant_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (siteFilter != null && !siteFilter.isBlank() && !"all".equalsIgnoreCase(siteFilter)) {
            try {
                params.add(UUID.fromString(siteFilter));
                sql.append(" AND d.site_id = ?");
            } catch (IllegalArgumentException ignored) {}
        }

        if (statusFilter != null && !statusFilter.isBlank() && !"all".equalsIgnoreCase(statusFilter)) {
            if ("ready".equalsIgnoreCase(statusFilter)) {
                sql.append(" AND d.status IN ('draft', 'pending', 'pending_docs')");
            } else {
                params.add(statusFilter.toLowerCase());
                sql.append(" AND d.status = ?");
            }
        }

        if (search != null && !search.isBlank()) {
            params.add("%" + search.trim().toLowerCase() + "%");
            params.add("%" + search.trim().toLowerCase() + "%");
            sql.append(" AND (LOWER(d.dispatch_number) LIKE ? OR LOWER(c.name) LIKE ?)");
        }

        sql.append(" ORDER BY d.created_at DESC");

        return jdbc.query(sql.toString(), (rs, i) -> {
            UUID jobId = getUuid(rs, "job_id");
            String jobNumber = rs.getString("job_number");
            List<String> jobs = jobNumber != null ? List.of(jobNumber) : Collections.emptyList();

            return new DispatchSummary(
                    (UUID) rs.getObject("id"),
                    rs.getString("dispatch_number"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("customer_name"),
                    getUuid(rs, "cpo_id"),
                    rs.getString("cpo_number"),
                    jobId,
                    jobNumber,
                    jobs,
                    (UUID) rs.getObject("site_id"),
                    rs.getString("site_name"),
                    getUuid(rs, "source_store_id"),
                    rs.getString("store_name"),
                    rs.getString("carrier"),
                    getUuid(rs, "dispatch_method_option_id"),
                    rs.getString("dispatch_method_label"),
                    rs.getString("vehicle_number"),
                    rs.getString("driver_name"),
                    rs.getString("lr_number"),
                    getLocalDate(rs, "dispatch_date"),
                    getLocalDate(rs, "expected_delivery"),
                    rs.getBigDecimal("total_qty"),
                    rs.getInt("line_count"),
                    rs.getString("status"),
                    rs.getBoolean("has_pod"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }, params.toArray());
    }

    DispatchDetail getDispatch(UUID tenantId, UUID dispatchId) {
        List<DispatchDetail> list = jdbc.query("""
                SELECT d.id, d.dispatch_number, d.customer_id, c.name AS customer_name,
                       d.cpo_id, cp.cpo_number, d.job_id, pj.job_number,
                       d.site_id, ps.name AS site_name, d.source_store_id,
                       COALESCE(d.source_store_name, st.name) AS store_name,
                       COALESCE(d.carrier, d.transporter) AS carrier,
                       d.dispatch_method_option_id, d.dispatch_method_label_snapshot AS dispatch_method_label,
                       d.vehicle_number, d.driver_name,
                       d.lr_number, d.dispatch_date, d.expected_delivery, d.actual_delivery_date,
                       d.delivery_condition, d.pod_reference, d.delivery_notes,
                       d.total_qty, d.line_count, d.status, d.has_pod,
                       d.quality_release_id, d.challan_number, d.challan_gcs_path,
                       d.created_at, d.updated_at
                FROM dispatches d
                JOIN customers c ON c.id = d.customer_id AND c.tenant_id = d.tenant_id
                JOIN physical_sites ps ON ps.id = d.site_id AND ps.tenant_id = d.tenant_id
                LEFT JOIN cpos cp ON cp.id = d.cpo_id AND cp.tenant_id = d.tenant_id
                LEFT JOIN production_jobs pj ON pj.id = d.job_id AND pj.tenant_id = d.tenant_id
                LEFT JOIN stores st ON st.id = d.source_store_id AND st.tenant_id = d.tenant_id
                WHERE d.tenant_id = ? AND d.id = ?
                """, (rs, i) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID jobId = getUuid(rs, "job_id");
            String jobNumber = rs.getString("job_number");
            List<String> jobs = jobNumber != null ? List.of(jobNumber) : Collections.emptyList();

            return new DispatchDetail(
                    id,
                    rs.getString("dispatch_number"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("customer_name"),
                    getUuid(rs, "cpo_id"),
                    rs.getString("cpo_number"),
                    jobId,
                    jobNumber,
                    jobs,
                    (UUID) rs.getObject("site_id"),
                    rs.getString("site_name"),
                    getUuid(rs, "source_store_id"),
                    rs.getString("store_name"),
                    null, // destination
                    rs.getString("carrier"),
                    getUuid(rs, "dispatch_method_option_id"),
                    rs.getString("dispatch_method_label"),
                    rs.getString("vehicle_number"),
                    rs.getString("driver_name"),
                    rs.getString("lr_number"),
                    getLocalDate(rs, "dispatch_date"),
                    getLocalDate(rs, "expected_delivery"),
                    getLocalDate(rs, "actual_delivery_date"),
                    rs.getString("delivery_condition"),
                    rs.getString("pod_reference"),
                    rs.getString("delivery_notes"),
                    null, // receivedByName (will load from pod_records if present)
                    null, // receivedAt
                    rs.getBoolean("has_pod"),
                    rs.getString("status"),
                    rs.getBigDecimal("total_qty"),
                    rs.getInt("line_count"),
                    getUuid(rs, "quality_release_id"),
                    null, // qualityReleaseStatus
                    null, // qualityReleaseBy
                    null, // qualityInspectionId
                    null, // qualityInspectionNumber
                    null, // qualityInspectionResult
                    null, // failedInspectionId
                    null, // failedInspectionNumber
                    null, // linkedNcrId
                    null, // linkedNcrNumber
                    rs.getString("challan_number"),
                    rs.getString("challan_gcs_path"),
                    Collections.emptyList(), // lines
                    Collections.emptyList(), // packages
                    Collections.emptyList(), // history
                    Collections.emptyList(), // linkedInvoices
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }, tenantId, dispatchId);

        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispatch not found");
        }

        DispatchDetail base = list.get(0);

        // Load POD Record if exists
        String receivedByName = null;
        Instant receivedAt = null;
        try {
            var podMap = jdbc.queryForMap(
                    "SELECT received_by, received_at FROM pod_records WHERE tenant_id = ? AND dispatch_id = ?",
                    tenantId, dispatchId);
            receivedByName = (String) podMap.get("received_by");
            var ts = (java.sql.Timestamp) podMap.get("received_at");
            if (ts != null) receivedAt = ts.toInstant();
        } catch (EmptyResultDataAccessException ignored) {}

        // Load Dispatch Lines
        List<DispatchLineDto> lines = jdbc.query("""
                SELECT dl.id, dl.dispatch_id, dl.cpo_line_id, dl.job_id, pj.job_number,
                       dl.item_id, it.code AS item_code, it.name AS item_name,
                       dl.lot_id, ml.lot_number, dl.quantity, dl.uom_id, uom.symbol AS uom_symbol,
                       dl.packing_spec_id, dl.clearance_cert_id, dl.line_no
                FROM dispatch_lines dl
                JOIN items it ON it.id = dl.item_id AND it.tenant_id = dl.tenant_id
                JOIN units_of_measure uom ON uom.id = dl.uom_id
                LEFT JOIN production_jobs pj ON pj.id = dl.job_id AND pj.tenant_id = dl.tenant_id
                LEFT JOIN material_lots ml ON ml.id = dl.lot_id AND ml.tenant_id = dl.tenant_id
                WHERE dl.tenant_id = ? AND dl.dispatch_id = ?
                ORDER BY dl.line_no ASC
                """, (rs, i) -> new DispatchLineDto(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("dispatch_id"),
                getUuid(rs, "cpo_line_id"),
                getUuid(rs, "job_id"),
                rs.getString("job_number"),
                (UUID) rs.getObject("item_id"),
                rs.getString("item_code"),
                rs.getString("item_name"),
                getUuid(rs, "lot_id"),
                rs.getString("lot_number"),
                rs.getBigDecimal("quantity"),
                (UUID) rs.getObject("uom_id"),
                rs.getString("uom_symbol"),
                getUuid(rs, "packing_spec_id"),
                getUuid(rs, "clearance_cert_id"),
                rs.getInt("line_no")
        ), tenantId, dispatchId);

        // Load Packages
        List<DispatchPackageDto> packages = jdbc.query("""
                SELECT id, dispatch_id, package_code, package_type, product_description,
                       quantity, uom, weight_kg, length_mm, width_mm, height_mm, packing_notes, created_at
                FROM dispatch_packages
                WHERE tenant_id = ? AND dispatch_id = ?
                ORDER BY created_at ASC
                """, (rs, i) -> new DispatchPackageDto(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("dispatch_id"),
                rs.getString("package_code"),
                rs.getString("package_type"),
                rs.getString("product_description"),
                rs.getBigDecimal("quantity"),
                rs.getString("uom"),
                rs.getBigDecimal("weight_kg"),
                rs.getBigDecimal("length_mm"),
                rs.getBigDecimal("width_mm"),
                rs.getBigDecimal("height_mm"),
                rs.getString("packing_notes"),
                rs.getTimestamp("created_at").toInstant()
        ), tenantId, dispatchId);

        // Check Quality Info for the Job
        UUID qualityReleaseId = base.qualityReleaseId();
        String qualityReleaseStatus = null;
        String qualityReleaseBy = null;
        UUID qualityInspectionId = null;
        String qualityInspectionNumber = null;
        String qualityInspectionResult = null;
        UUID failedInspectionId = null;
        String failedInspectionNumber = null;
        UUID linkedNcrId = null;
        String linkedNcrNumber = null;

        if (qualityReleaseId != null) {
            try {
                var cert = jdbc.queryForMap("""
                        SELECT cc.status, u.display_name AS issued_by_name
                          FROM clearance_certificates cc
                          LEFT JOIN app_users u ON u.id = cc.issued_by
                         WHERE cc.tenant_id = ? AND cc.id = ?
                        """, tenantId, qualityReleaseId);
                qualityReleaseStatus = (String) cert.get("status");
                qualityReleaseBy = (String) cert.get("issued_by_name");
            } catch (EmptyResultDataAccessException ignored) {}
        }

        if (base.jobId() != null) {
            try {
                var insp = jdbc.queryForMap("""
                        SELECT qi.id, qi.inspection_number, qi.result, u.display_name AS inspector_name
                        FROM quality_inspections qi
                        LEFT JOIN app_users u ON u.id = qi.inspector_id
                        WHERE qi.tenant_id = ? AND qi.reference_id = ?
                        ORDER BY qi.created_at DESC LIMIT 1
                        """, tenantId, base.jobId());
                qualityInspectionId = (UUID) insp.get("id");
                qualityInspectionNumber = (String) insp.get("inspection_number");
                qualityInspectionResult = (String) insp.get("result");

                if ("fail".equals(qualityInspectionResult)) {
                    failedInspectionId = qualityInspectionId;
                    failedInspectionNumber = qualityInspectionNumber;
                }
            } catch (EmptyResultDataAccessException ignored) {}

            try {
                var ncr = jdbc.queryForMap("""
                        SELECT n.id, n.ncr_number
                        FROM ncrs n
                        JOIN quality_inspections qi ON qi.id = n.inspection_id
                        WHERE n.tenant_id = ? AND qi.reference_id = ?
                        ORDER BY n.created_at DESC LIMIT 1
                        """, tenantId, base.jobId());
                linkedNcrId = (UUID) ncr.get("id");
                linkedNcrNumber = (String) ncr.get("ncr_number");
            } catch (EmptyResultDataAccessException ignored) {}
        }

        List<LinkedInvoiceDto> linkedInvoices = jdbc.query("""
                SELECT id, invoice_number, status, total_amount, invoice_date
                FROM invoices
                WHERE tenant_id = ? AND dispatch_id = ?
                ORDER BY created_at DESC
                """, (rs, i) -> new LinkedInvoiceDto(
                (UUID) rs.getObject("id"),
                rs.getString("invoice_number"),
                rs.getString("status"),
                rs.getBigDecimal("total_amount"),
                getLocalDate(rs, "invoice_date")
        ), tenantId, dispatchId);

        List<DispatchHistoryDto> history = jdbc.query("""
                SELECT a.id, a.action, u.display_name AS actor_name, a.created_at
                FROM audit_events a
                LEFT JOIN app_users u ON u.id = a.actor_id
                WHERE a.tenant_id = ? AND a.entity_type = 'dispatch' AND a.entity_id = ?
                ORDER BY a.created_at DESC
                """, (rs, i) -> {
            String act = rs.getString("action");
            String title = HISTORY_TITLES.getOrDefault(act, "Dispatch Updated (" + act + ")");
            return new DispatchHistoryDto(
                    (UUID) rs.getObject("id"),
                    act,
                    title,
                    "Action recorded by " + (rs.getString("actor_name") != null ? rs.getString("actor_name") : "system"),
                    rs.getString("actor_name"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }, tenantId, dispatchId);

        return new DispatchDetail(
                base.id(),
                base.dispatchNumber(),
                base.customerId(),
                base.customerName(),
                base.cpoId(),
                base.cpoNumber(),
                base.jobId(),
                base.jobNumber(),
                base.jobs(),
                base.sourceSiteId(),
                base.sourceSiteName(),
                base.sourceStoreId(),
                base.sourceStoreName(),
                base.destination(),
                base.carrier(),
                base.dispatchMethodOptionId(),
                base.dispatchMethodLabel(),
                base.vehicleNumber(),
                base.driverName(),
                base.lrNumber(),
                base.dispatchDate(),
                base.expectedDelivery(),
                base.actualDeliveryDate(),
                base.deliveryCondition(),
                base.podReference(),
                base.deliveryNotes(),
                receivedByName,
                receivedAt,
                base.hasPoD(),
                base.status(),
                base.totalQty(),
                lines.size(),
                qualityReleaseId,
                qualityReleaseStatus,
                qualityReleaseBy,
                qualityInspectionId,
                qualityInspectionNumber,
                qualityInspectionResult,
                failedInspectionId,
                failedInspectionNumber,
                linkedNcrId,
                linkedNcrNumber,
                base.challanNumber(),
                base.challanGcsPath(),
                lines,
                packages,
                history,
                linkedInvoices,
                base.createdAt(),
                base.updatedAt()
        );
    }

    private static UUID getUuid(ResultSet rs, String col) throws SQLException {
        Object obj = rs.getObject(col);
        return obj instanceof UUID u ? u : null;
    }

    private static LocalDate getLocalDate(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d != null ? d.toLocalDate() : null;
    }
}
