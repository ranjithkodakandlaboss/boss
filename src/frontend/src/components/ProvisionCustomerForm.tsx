import { useState, type FormEvent } from "react";
import { PlatformAdminApiError } from "../api/platformAdminApi";
import {
  TenantProvisionRequestEntityType,
  TenantProvisionRequestIndustryType,
} from "../api/generated/platform-admin";
import type {
  ApiResponse,
  ProvisionResult,
  TenantProvisionRequest,
} from "../api/platformAdminApi";

type ProvisionResponse = ApiResponse<ProvisionResult | undefined>;
type FieldErrors = Partial<Record<keyof TenantProvisionRequest, string>>;

interface ProvisionCustomerFormProps {
  onCancel: () => void;
  onSubmit: (request: TenantProvisionRequest) => Promise<ProvisionResponse>;
  onCreated: (response: ProvisionResponse, initialAdminEmail: string, tenantName: string, tenantSlug: string) => void;
}

export function generateSlug(name: string): string {
  return name
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 63);
}

export function validateProvisionRequest(values: TenantProvisionRequest): FieldErrors {
  const errors: FieldErrors = {};
  const requiredFields: Array<keyof TenantProvisionRequest> = [
    "customerGroupName",
    "tenantName",
    "tenantSlug",
    "legalEntityName",
    "pan",
    "entityType",
    "siteCode",
    "siteName",
    "industryType",
  ];

  for (const field of requiredFields) {
    if (!String(values[field] ?? "").trim()) errors[field] = "This field is required.";
  }
  if (values.tenantSlug) {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(values.tenantSlug)) {
      errors.tenantSlug = "Enter a valid slug with lowercase letters, numbers, and single hyphens (for example, acme-mfg).";
    } else if (values.tenantSlug.length < 2 || values.tenantSlug.length > 63) {
      errors.tenantSlug = "Slug must be between 2 and 63 characters.";
    }
  }
  if (values.customerGroupSlug) {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(values.customerGroupSlug)) {
      errors.customerGroupSlug = "Enter a valid slug with lowercase letters, numbers, and single hyphens.";
    }
  }
  if (values.pan && !/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(values.pan)) {
    errors.pan = "Enter a valid 10-character PAN in uppercase (for example, ABCDE1234F).";
  }
  if (values.gstin && !/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$/.test(values.gstin.trim())) {
    errors.gstin = "Enter a valid 15-character GSTIN (for example, 29ABCDE1234F1Z5).";
  }
  if (
    values.entityType &&
    !Object.values(TenantProvisionRequestEntityType).includes(values.entityType)
  ) {
    errors.entityType = "Select an entity type from the contract options.";
  }
  if (
    values.industryType &&
    !Object.values(TenantProvisionRequestIndustryType).includes(values.industryType)
  ) {
    errors.industryType = "Select an industry type from the contract options.";
  }
  if (values.adminEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.adminEmail)) {
    errors.adminEmail = "Enter a valid email address.";
  }
  return errors;
}

export function ProvisionCustomerForm({
  onCancel,
  onSubmit,
  onCreated,
}: ProvisionCustomerFormProps) {
  const [values, setValues] = useState<TenantProvisionRequest>({
    customerGroupName: "",
    customerGroupSlug: "",
    tenantName: "",
    tenantSlug: "",
    planTier: "pilot",
    legalEntityName: "",
    pan: "",
    entityType: TenantProvisionRequestEntityType.PRIVATE_LIMITED,
    gstin: "",
    registeredAddress: "",
    siteCode: "",
    siteName: "",
    siteAddress: "",
    adminFullName: "",
    adminEmail: "",
    adminPhone: "",
    industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
  });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [submitError, setSubmitError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [slugCustomized, setSlugCustomized] = useState(false);
  const [groupSlugCustomized, setGroupSlugCustomized] = useState(false);

  function updateField<K extends keyof TenantProvisionRequest>(
    key: K,
    value: TenantProvisionRequest[K],
  ) {
    setValues((current) => ({ ...current, [key]: value }));
    setErrors((current) => ({ ...current, [key]: undefined }));
    setSubmitError("");
  }

  function handleGroupNameChange(name: string) {
    const derivedSlug = generateSlug(name);
    setValues((current) => ({
      ...current,
      customerGroupName: name,
      customerGroupSlug: groupSlugCustomized ? current.customerGroupSlug : derivedSlug,
      tenantSlug: slugCustomized ? current.tenantSlug : derivedSlug,
    }));
    setErrors((current) => ({
      ...current,
      customerGroupName: undefined,
    }));
    setSubmitError("");
  }

  function handleTenantNameChange(name: string) {
    const derivedSlug = generateSlug(name);
    setValues((current) => ({
      ...current,
      tenantName: name,
      tenantSlug: slugCustomized ? current.tenantSlug : derivedSlug,
    }));
    setErrors((current) => ({ ...current, tenantName: undefined, tenantSlug: undefined }));
    setSubmitError("");
  }

  function handleTenantSlugChange(slug: string) {
    const prevAuto = generateSlug(values.tenantName);
    if (!slugCustomized && prevAuto && slug.startsWith(prevAuto) && slug.length > prevAuto.length) {
      setSlugCustomized(true);
      updateField("tenantSlug", slug.slice(prevAuto.length).toLowerCase());
      return;
    }
    setSlugCustomized(true);
    updateField("tenantSlug", slug.toLowerCase());
  }

  function handleGroupSlugChange(slug: string) {
    const prevAuto = generateSlug(values.customerGroupName);
    if (!groupSlugCustomized && prevAuto && slug.startsWith(prevAuto) && slug.length > prevAuto.length) {
      setGroupSlugCustomized(true);
      updateField("customerGroupSlug", slug.slice(prevAuto.length).toLowerCase());
      return;
    }
    setGroupSlugCustomized(true);
    updateField("customerGroupSlug", slug.toLowerCase());
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextErrors = validateProvisionRequest(values);
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors);
      setSubmitError("Review the highlighted fields before submitting.");
      return;
    }
    setSubmitting(true);
    setSubmitError("");
    try {
      const response = await onSubmit(values);
      onCreated(response, values.adminEmail ?? "", values.tenantName, values.tenantSlug);
    } catch (error) {
      if (error instanceof PlatformAdminApiError) {
        setSubmitError(error.status === 0 ? error.message : `${error.message} (HTTP ${error.status})`);
      } else {
        setSubmitError(error instanceof Error ? error.message : "Customer provisioning failed.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="provision-form" onSubmit={handleSubmit} noValidate>
      <header className="page-header">
        <div>
          <button type="button" className="back-link" onClick={onCancel}>
            ← Customer directory
          </button>
          <h1>Create customer</h1>
          <p className="subheading">Register the company and provision its first BOSS tenant.</p>
        </div>
      </header>

      {submitError && <div className="banner banner-error" role="alert">{submitError}</div>}

      <section className="form-section" aria-labelledby="group-heading">
        <div className="section-heading">
          <span className="section-number">01</span>
          <div>
            <h2 id="group-heading">Customer group</h2>
            <p>Group related BOSS tenants under one customer account.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="customerGroupName"
            label="Customer group name"
            value={values.customerGroupName}
            error={errors.customerGroupName}
            onChange={handleGroupNameChange}
            required
          />
          <TextField
            name="customerGroupSlug"
            label="Customer group slug"
            hint="Optional. Auto-derived from the company name if left untouched."
            value={values.customerGroupSlug ?? ""}
            error={errors.customerGroupSlug}
            onChange={handleGroupSlugChange}
          />
        </div>
      </section>

      <section className="form-section" aria-labelledby="tenant-heading">
        <div className="section-heading">
          <span className="section-number">02</span>
          <div>
            <h2 id="tenant-heading">Tenant</h2>
            <p>Set the workspace name, URL slug and plan tier.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="tenantName"
            label="Tenant name"
            value={values.tenantName}
            error={errors.tenantName}
            onChange={handleTenantNameChange}
            required
          />
          <TextField
            name="tenantSlug"
            label="Tenant slug"
            hint="Auto-generated URL slug. Edit to customize."
            value={values.tenantSlug}
            error={errors.tenantSlug}
            onChange={handleTenantSlugChange}
            required
          />
          <TextField
            name="planTier"
            label="Plan tier"
            hint="The API defaults this value to pilot."
            value={values.planTier}
            error={errors.planTier}
            onChange={(value) => updateField("planTier", value)}
          />

          <div
            className="workspace-url-preview"
            style={{
              gridColumn: "1 / -1",
              marginTop: "-4px",
              padding: "12px 16px",
              background: "#f8fafc",
              border: "1px solid #e2e8f0",
              borderRadius: "6px",
              fontSize: "12px",
              color: "#334155",
            }}
          >
            <div style={{ fontWeight: 600, color: "#0f172a", marginBottom: "4px" }}>
              Company workspace slug
            </div>
            <div style={{ fontFamily: "monospace", color: "#2563eb", fontSize: "13px" }}>
              {values.tenantSlug || "{tenant-slug}"}
            </div>
            <div style={{ fontSize: "11px", color: "#64748b", marginTop: "4px" }}>
              The invitation email uses the company hostname configured on the server.
            </div>
          </div>
        </div>
      </section>

      <section className="form-section" aria-labelledby="entity-heading">
        <div className="section-heading">
          <span className="section-number">03</span>
          <div>
            <h2 id="entity-heading">Legal entity</h2>
            <p>Enter the registered entity details used to set up this tenant.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="legalEntityName"
            label="Legal entity name"
            value={values.legalEntityName}
            error={errors.legalEntityName}
            onChange={(value) => updateField("legalEntityName", value)}
            required
          />
          <TextField
            name="pan"
            label="PAN"
            hint="10 characters, for example ABCDE1234F."
            value={values.pan}
            error={errors.pan}
            onChange={(value) => updateField("pan", value.toUpperCase())}
            required
            maxLength={10}
          />
          <SelectField
            name="entityType"
            label="Entity type"
            value={values.entityType}
            error={errors.entityType}
            options={Object.values(TenantProvisionRequestEntityType)}
            onChange={(value) => {
              const selected = Object.values(TenantProvisionRequestEntityType).find((option) => option === value);
              if (selected) updateField("entityType", selected);
            }}
          />
          <TextField
            name="gstin"
            label="GSTIN"
            hint="Optional. 15-character GST identification number."
            value={values.gstin ?? ""}
            error={errors.gstin}
            onChange={(value) => updateField("gstin", value.toUpperCase())}
            maxLength={15}
          />
          <TextField
            name="registeredAddress"
            label="Registered address"
            hint="Registered office address of the legal entity."
            value={values.registeredAddress ?? ""}
            error={errors.registeredAddress}
            onChange={(value) => updateField("registeredAddress", value)}
          />
        </div>
      </section>

      <section className="form-section" aria-labelledby="site-heading">
        <div className="section-heading">
          <span className="section-number">04</span>
          <div>
            <h2 id="site-heading">Initial site</h2>
            <p>Every tenant begins with one primary physical site.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="siteCode"
            label="Site code"
            hint="A short identifier, for example SITE-01."
            value={values.siteCode}
            error={errors.siteCode}
            onChange={(value) => updateField("siteCode", value.toUpperCase())}
            required
          />
          <TextField
            name="siteName"
            label="Site name"
            value={values.siteName}
            error={errors.siteName}
            onChange={(value) => updateField("siteName", value)}
            required
          />
          <TextField
            name="siteAddress"
            label="Site address"
            hint="Physical address of the manufacturing site or warehouse."
            value={values.siteAddress ?? ""}
            error={errors.siteAddress}
            onChange={(value) => updateField("siteAddress", value)}
          />
        </div>
      </section>

      <section className="form-section" aria-labelledby="admin-heading">
        <div className="section-heading">
          <span className="section-number">05</span>
          <div>
            <h2 id="admin-heading">Initial customer administrator</h2>
            <p>Owner-level account that receives the setup invitation.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="adminFullName"
            label="Administrator full name"
            hint="Full legal name of the organization owner."
            value={values.adminFullName ?? ""}
            error={errors.adminFullName}
            onChange={(value) => updateField("adminFullName", value)}
          />
          <TextField
            name="adminEmail"
            label="Administrator email"
            hint="Invitation email will be delivered to this address."
            value={values.adminEmail ?? ""}
            error={errors.adminEmail}
            onChange={(value) => updateField("adminEmail", value)}
            type="email"
          />
          <TextField
            name="adminPhone"
            label="Administrator phone"
            hint="Optional. Contact phone number."
            value={values.adminPhone ?? ""}
            error={errors.adminPhone}
            onChange={(value) => updateField("adminPhone", value)}
          />
        </div>
      </section>

      <section className="form-section" aria-labelledby="industry-heading">
        <div className="section-heading">
          <span className="section-number">06</span>
          <div>
            <h2 id="industry-heading">Industry template</h2>
            <p>Seeds baseline master data and configuration options.</p>
          </div>
        </div>
        <div className="field-grid">
          <SelectField
            name="industryType"
            label="Industry type"
            value={values.industryType}
            error={errors.industryType}
            options={Object.values(TenantProvisionRequestIndustryType)}
            onChange={(value) => {
              const selected = Object.values(TenantProvisionRequestIndustryType).find((option) => option === value);
              if (selected) updateField("industryType", selected);
            }}
          />
        </div>
      </section>

      <footer className="form-actions">
        <button type="button" className="button button-secondary" onClick={onCancel} disabled={submitting}>
          Cancel
        </button>
        <button type="submit" className="button button-primary" disabled={submitting}>
          {submitting ? "Creating customer..." : "Create customer"}
        </button>
      </footer>
    </form>
  );
}

function TextField({
  name,
  label,
  value,
  error,
  hint,
  required,
  onChange,
  type = "text",
  maxLength,
}: {
  name: string;
  label: string;
  value: string;
  error?: string;
  hint?: string;
  required?: boolean;
  onChange: (value: string) => void;
  type?: string;
  maxLength?: number;
}) {
  const id = `field-${name}`;
  return (
    <div className={`field ${error ? "has-error" : ""}`}>
      <label htmlFor={id}>
        {label} {required && <span className="required-star">*</span>}
      </label>
      <input
        id={id}
        name={name}
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        maxLength={maxLength}
      />
      {hint && !error && <span id={`${id}-hint`} className="field-hint">{hint}</span>}
      {error && <span id={`${id}-error`} className="field-error" role="alert">{error}</span>}
    </div>
  );
}

function SelectField({
  name,
  label,
  value,
  error,
  options,
  onChange,
}: {
  name: string;
  label: string;
  value: string;
  error?: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  const id = `field-${name}`;
  return (
    <div className={`field ${error ? "has-error" : ""}`}>
      <label htmlFor={id}>{label}</label>
      <select id={id} name={name} value={value} onChange={(event) => onChange(event.target.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
      {error && <span className="field-error" role="alert">{error}</span>}
    </div>
  );
}
