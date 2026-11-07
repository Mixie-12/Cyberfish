# Google Cloud and Firebase for DeKoi (ADR 0009): one project used for
# nothing else, Firebase for push notifications, the Android app with its
# signing certificates (Firebase creates the Android sign-in client from
# them), and a service account that may only send FCM messages.
#
# Apply from this directory with `tofu apply`, then run ./publish.sh to copy
# the public values into GitHub. No secret ever enters the state.

terraform {
  required_version = ">= 1.8"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "= 8.10.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "= 8.10.0"
    }
  }
}

variable "project_id" {
  description = "Globally unique Google Cloud project ID."
  type        = string
  default     = "dekoi-cyberfish"
}

variable "android_package" {
  type    = string
  default = "no.cyberfish.dekoi"
}

# Release key: docs/STATUS.md, "Secrets". Debug key: the Android SDK's debug
# keystore on the development machine, for testing debug builds.
variable "certificate_sha1" {
  type = list(string)
  default = [
    "334b2b9243518c19c1dbee2550453857f39fc977",
    "6def2498d6e7ae43b6c963669f6bce6789f9f4c5",
  ]
}

variable "certificate_sha256" {
  type = list(string)
  default = [
    "7f5759c5015bfc7a347a72353554079196e621585a9fee69447586a7e8436741",
    "588bd196febe62346e1ecf5e06f5312f131c2a8fd5dac1eb090ea1c93619edda",
  ]
}

# Firebase management APIs need a quota project; the project itself is
# created without one.
provider "google-beta" {
  user_project_override = true
  billing_project       = var.project_id
}

provider "google-beta" {
  alias                 = "bootstrap"
  user_project_override = false
}

resource "google_project" "dekoi" {
  provider        = google-beta.bootstrap
  project_id      = var.project_id
  name            = "DeKoi"
  labels          = { firebase = "enabled" }
  deletion_policy = "PREVENT"
}

resource "google_project_service" "apis" {
  provider = google-beta.bootstrap
  project  = google_project.dekoi.project_id
  for_each = toset([
    "cloudresourcemanager.googleapis.com",
    "serviceusage.googleapis.com",
    "iam.googleapis.com",
    "firebase.googleapis.com",
    "fcm.googleapis.com",
    "firebaseinstallations.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

resource "google_firebase_project" "dekoi" {
  provider   = google-beta
  project    = google_project.dekoi.project_id
  depends_on = [google_project_service.apis]
}

resource "google_firebase_android_app" "dekoi" {
  provider      = google-beta
  project       = google_firebase_project.dekoi.project
  display_name  = "DeKoi"
  package_name  = var.android_package
  sha1_hashes   = var.certificate_sha1
  sha256_hashes = var.certificate_sha256
}

data "google_firebase_android_app_config" "dekoi" {
  provider = google-beta
  project  = google_firebase_project.dekoi.project
  app_id   = google_firebase_android_app.dekoi.app_id
}

resource "google_service_account" "fcm_sender" {
  provider     = google-beta
  project      = google_project.dekoi.project_id
  account_id   = "fcm-sender"
  display_name = "DeKoi push notifications (notify Edge Function)"
  depends_on   = [google_project_service.apis]
}

resource "google_project_iam_member" "fcm_sender" {
  provider = google-beta
  project  = google_project.dekoi.project_id
  role     = "roles/firebasecloudmessaging.admin"
  member   = "serviceAccount:${google_service_account.fcm_sender.email}"
}

locals {
  services = jsondecode(base64decode(data.google_firebase_android_app_config.dekoi.config_file_contents))
  client   = local.services.client[0]
  # Firebase creates a web OAuth client for Google sign-in (client_type 3).
  web_clients = [for c in try(local.client.oauth_client, []) : c.client_id if c.client_type == 3]
}

output "firebase_project_id" {
  value = local.services.project_info.project_id
}

output "firebase_sender_id" {
  value = local.services.project_info.project_number
}

output "firebase_app_id" {
  value = local.client.client_info.mobilesdk_app_id
}

output "firebase_api_key" {
  value = local.client.api_key[0].current_key
}

output "google_web_client_id" {
  value = length(local.web_clients) > 0 ? local.web_clients[0] : ""
}

output "fcm_sender_email" {
  value = google_service_account.fcm_sender.email
}
