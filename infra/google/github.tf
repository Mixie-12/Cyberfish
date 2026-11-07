# Keyless access from GitHub Actions through workload identity federation
# (ADR 0022). Each pool trusts exactly one workflow on one kind of ref, so a
# token from any other workflow, branch, tag or repository is refused at the
# token exchange, before IAM is asked.

# The numeric ID survives renames and cannot be taken over by a new repository
# with the same name.
variable "github_repository_id" {
  type    = string
  default = "1449211150"
}

variable "github_workflows" {
  type    = string
  default = "Mixie-12/Cyberfish/.github/workflows"
}

locals {
  github_pools = {
    # Rotates the FCM sender key, from main (rotate-keys.yml).
    "github-rotation" = join(" && ", [
      "assertion.repository_id == '${var.github_repository_id}'",
      "assertion.ref == 'refs/heads/main'",
      "assertion.environment == 'production'",
      "assertion.workflow_ref == '${var.github_workflows}/rotate-keys.yml@refs/heads/main'",
    ])
    # Signs device images, only from a release tag (device-images.yml).
    "github-release" = join(" && ", [
      "assertion.repository_id == '${var.github_repository_id}'",
      "assertion.ref.startsWith('refs/tags/v')",
      "assertion.environment == 'release'",
      "assertion.workflow_ref.startsWith('${var.github_workflows}/device-images.yml@refs/tags/v')",
    ])
  }
}

resource "google_iam_workload_identity_pool" "github" {
  provider                  = google-beta
  for_each                  = local.github_pools
  project                   = google_project.dekoi.project_id
  workload_identity_pool_id = each.key
  depends_on                = [google_project_service.apis]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  provider                           = google-beta
  for_each                           = local.github_pools
  project                            = google_project.dekoi.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[each.key].workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  attribute_mapping                  = { "google.subject" = "assertion.sub" }
  attribute_condition                = each.value
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# The rotation workflow acts as this account, which may only manage the keys
# of fcm-sender.
resource "google_service_account" "fcm_key_rotator" {
  provider     = google-beta
  project      = google_project.dekoi.project_id
  account_id   = "fcm-key-rotator"
  display_name = "Rotates the fcm-sender key (GitHub rotate-keys workflow)"
  depends_on   = [google_project_service.apis]
}

resource "google_service_account_iam_member" "fcm_key_rotator_github" {
  provider           = google-beta
  service_account_id = google_service_account.fcm_key_rotator.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github["github-rotation"].name}/*"
}

resource "google_service_account_iam_member" "fcm_sender_keys" {
  provider           = google-beta
  service_account_id = google_service_account.fcm_sender.name
  role               = "roles/iam.serviceAccountKeyAdmin"
  member             = "serviceAccount:${google_service_account.fcm_key_rotator.email}"
}

output "github_rotation_provider" {
  value = google_iam_workload_identity_pool_provider.github["github-rotation"].name
}

output "fcm_key_rotator_email" {
  value = google_service_account.fcm_key_rotator.email
}
