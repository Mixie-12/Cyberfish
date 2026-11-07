# The device image signing key in Cloud KMS (ADR 0022). The private key is
# created in an HSM and cannot be exported; the release workflow can only ask
# KMS to sign, and only from a v* tag in the release environment.
#
# Cloud KMS needs a billing account. Nothing here is created until the owner
# sets billing_account (about 1 USD a month for the key version).

variable "billing_account" {
  description = "Billing account ID (XXXXXX-XXXXXX-XXXXXX), or empty for no Cloud KMS."
  type        = string
  default     = ""
}

variable "kms_location" {
  type    = string
  default = "europe-north1"
}

locals {
  signing = var.billing_account == "" ? toset([]) : toset(["image-signing"])
}

resource "google_billing_project_info" "dekoi" {
  provider        = google-beta.bootstrap
  for_each        = local.signing
  project         = google_project.dekoi.project_id
  billing_account = var.billing_account
}

resource "google_project_service" "kms" {
  provider           = google-beta.bootstrap
  for_each           = local.signing
  project            = google_project.dekoi.project_id
  service            = "cloudkms.googleapis.com"
  disable_on_destroy = false
  depends_on         = [google_billing_project_info.dekoi]
}

# Key rings and keys cannot be deleted in KMS; prevent_destroy keeps a plan
# from trying.
resource "google_kms_key_ring" "dekoi" {
  provider   = google-beta
  for_each   = local.signing
  project    = google_project.dekoi.project_id
  name       = "dekoi"
  location   = var.kms_location
  depends_on = [google_project_service.kms]
}

resource "google_kms_crypto_key" "image_signing" {
  provider = google-beta
  for_each = local.signing
  name     = each.key
  key_ring = google_kms_key_ring.dekoi[each.key].id
  purpose  = "ASYMMETRIC_SIGN"
  version_template {
    algorithm        = "EC_SIGN_P256_SHA256"
    protection_level = "HSM"
  }
  lifecycle {
    prevent_destroy = true
  }
}

data "google_kms_crypto_key_version" "image_signing" {
  provider   = google-beta
  for_each   = local.signing
  crypto_key = google_kms_crypto_key.image_signing[each.key].id
  version    = 1
}

resource "google_service_account" "image_signer" {
  provider     = google-beta
  for_each     = local.signing
  project      = google_project.dekoi.project_id
  account_id   = "image-signer"
  display_name = "Signs device images (GitHub device-images workflow, release tags)"
  depends_on   = [google_project_service.apis]
}

resource "google_service_account_iam_member" "image_signer_github" {
  provider           = google-beta
  for_each           = local.signing
  service_account_id = google_service_account.image_signer[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github["github-release"].name}/*"
}

# cosign reads the key and its versions, then asks KMS to sign.
resource "google_kms_crypto_key_iam_member" "image_signer" {
  provider      = google-beta
  for_each      = var.billing_account == "" ? toset([]) : toset(["roles/cloudkms.signerVerifier", "roles/cloudkms.viewer"])
  crypto_key_id = google_kms_crypto_key.image_signing["image-signing"].id
  role          = each.value
  member        = "serviceAccount:${google_service_account.image_signer["image-signing"].email}"
}

output "github_release_provider" {
  value = google_iam_workload_identity_pool_provider.github["github-release"].name
}

output "image_signer_email" {
  value = join("", [for s in google_service_account.image_signer : s.email])
}

output "cosign_kms_key" {
  value = join("", [for k in google_kms_crypto_key.image_signing : "gcpkms://${k.id}/cryptoKeyVersions/1"])
}

# The public key in the PEM format cosign.pub uses; written to the card
# (docs/runbooks/rotate-keys.md, "Switch new cards to the Cloud KMS key").
output "image_signing_public_key" {
  value = join("", [for k in data.google_kms_crypto_key_version.image_signing : k.public_key[0].pem])
}
