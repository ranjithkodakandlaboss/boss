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
  onCreated: (response: ProvisionResponse, initialAdminEmail: string, tenantName: string) => void;
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
  if (values.pan && !/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(values.pan)) {
    errors.pan = "Enter a valid 10-character PAN in uppercase (for example, ABCDE1234F).";
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
    siteCode: "",
    siteName: "",
    adminEmail: "",
    industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
  });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [submitError, setSubmitError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  function updateField<K extends keyof TenantProvisionRequest>(
    key: K,
    value: TenantProvisionRequest[K],
  ) {
    setValues((current) => ({ ...current, [key]: value }));
    setErrors((current) => ({ ...current, [key]: undefined }));
    setSubmitError("");
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextErrors = validateProvisionRequest(values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    setSubmitting(true);
    setSubmitError("");
    const initialAdminEmail = values.adminEmail?.trim() ?? "";
    const request: TenantProvisionRequest = {
      ...values,
      customerGroupSlug: values.customerGroupSlug?.trim() || undefined,
      tenantSlug: values.tenantSlug.trim(),
      planTier: values.planTier.trim() || "pilot",
      pan: values.pan.trim(),
      adminEmail: initialAdminEmail || undefined,
    };

    try {
      const response = await onSubmit(request);
      onCreated(response, initialAdminEmail, values.tenantName);
    } catch (error) {
      setSubmitError(error instanceof PlatformAdminApiError
        ? `${error.message} (HTTP ${error.status})`
        : error instanceof Error ? error.message : "The request could not be completed.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="form-page" noValidate onSubmit={handleSubmit}>
      <header className="page-heading">
        <div>
          <p className="eyebrow">CUSTOMER ONBOARDING</p>
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
            onChange={(value) => updateField("customerGroupName", value)}
            required
          />
          <TextField
            name="customerGroupSlug"
            label="Customer group slug"
            hint="Optional. If omitted, the API derives it from the group name."
            value={values.customerGroupSlug ?? ""}
            error={errors.customerGroupSlug}
            onChange={(value) => updateField("customerGroupSlug", value)}
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
            onChange={(value) => updateField("tenantName", value)}
            required
          />
          <TextField
            name="tenantSlug"
            label="Tenant slug"
            hint="Used to identify this workspace."
            value={values.tenantSlug}
            error={errors.tenantSlug}
            onChange={(value) => updateField("tenantSlug", value)}
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
        </div>
      </section>

      <section className="form-section" aria-labelledby="site-heading">
        <div className="section-heading">
          <span className="section-number">04</span>
          <div>
            <h2 id="site-heading">Initial site</h2>
            <p>Create the first physical site for this tenant.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="siteCode"
            label="Site code"
            value={values.siteCode}
            error={errors.siteCode}
            onChange={(value) => updateField("siteCode", value)}
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

      <section className="form-section" aria-labelledby="invite-heading">
        <div className="section-heading">
          <span className="section-number">05</span>
          <div>
            <h2 id="invite-heading">Initial administrator</h2>
            <p>Optional. If provided, the API attempts to create an administrator invitation.</p>
          </div>
        </div>
        <div className="field-grid">
          <TextField
            name="adminEmail"
            label="Administrator email"
            type="email"
            value={values.adminEmail ?? ""}
            error={errors.adminEmail}
            onChange={(value) => updateField("adminEmail", value)}
          />
        </div>
      </section>

      <div className="form-actions">
        <button className="button button-quiet" type="button" onClick={onCancel} disabled={submitting}>
          Cancel
        </button>
        <button className="button button-primary" type="submit" disabled={submitting}>
          {submitting ? "Provisioning…" : "Create customer"}
        </button>
      </div>
    </form>
  );
}

interface TextFieldProps {
  name: keyof TenantProvisionRequest;
  label: string;
  value: string;
  error?: string;
  hint?: string;
  type?: string;
  required?: boolean;
  maxLength?: number;
  onChange: (value: string) => void;
}

function TextField({
  name,
  label,
  value,
  error,
  hint,
  type = "text",
  required = false,
  maxLength,
  onChange,
}: TextFieldProps) {
  const helpId = `${name}-help`;
  const errorId = `${name}-error`;
  return (
    <div className="field">
      <label htmlFor={name}>
        {label} {required && <span className="required-mark" aria-hidden="true">*</span>}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        value={value}
        maxLength={maxLength}
        required={required}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? errorId : hint ? helpId : undefined}
        onChange={(event) => onChange(event.currentTarget.value)}
      />
      {hint && !error && <span className="field-hint" id={helpId}>{hint}</span>}
      {error && <span className="field-error" id={errorId}>{error}</span>}
    </div>
  );
}

interface SelectFieldProps {
  name: string;
  label: string;
  value: string;
  error?: string;
  options: string[];
  onChange: (value: string) => void;
}

function SelectField({ name, label, value, error, options, onChange }: SelectFieldProps) {
  const errorId = `${name}-error`;
  return (
    <div className="field">
      <label htmlFor={name}>{label} <span className="required-mark" aria-hidden="true">*</span></label>
      <select
        id={name}
        name={name}
        value={value}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => onChange(event.currentTarget.value)}
      >
        {options.map((option) => (
          <option value={option} key={option}>{formatOption(option)}</option>
        ))}
      </select>
      {error && <span className="field-error" id={errorId}>{error}</span>}
    </div>
  );
}

function formatOption(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ")
    .replace("Llp", "LLP")
    .replace("Huf", "HUF");
}
