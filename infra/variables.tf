variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "asia-south1"
}

variable "env" {
  description = "Environment name: dev | staging | prod"
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.env)
    error_message = "env must be dev, staging, or prod"
  }
}

variable "image_tag" {
  description = "Docker image tag to deploy"
  type        = string
  default     = "latest"
}

variable "admin_image_tag" {
  description = "Initial container image tag used when OpenTofu creates the Platform Admin service"
  type        = string
  default     = "bootstrap-v1"
}

variable "admin_service_enabled" {
  description = "Enable Stage 2 creation of the Platform Admin Cloud Run service after bootstrap prerequisites are ready"
  type        = bool
  default     = false
}

variable "customer_firebase_project_id" {
  description = "Firebase project used by the customer-facing boss-api service"
  type        = string
  validation {
    condition     = length(trimspace(var.customer_firebase_project_id)) > 0
    error_message = "customer_firebase_project_id must identify the customer Firebase project."
  }
}

variable "admin_firebase_project_id" {
  description = "Separate Firebase project used by boss-admin-api"
  type        = string
  default     = ""
}

variable "monitoring_notification_channel_ids" {
  description = "Existing Cloud Monitoring notification channel IDs for the Cloud Run 5xx alert. Empty until a channel is approved."
  type        = list(string)
  default     = []
}
