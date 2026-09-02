output "deploy_role_arn" {
  value       = aws_iam_role.deploy.arn
  description = "Add as AWS_DEPLOY_ROLE_ARN secret in the repos."
}

output "recover_role_arn" {
  value       = var.create_recover_role ? aws_iam_role.recover[0].arn : ""
  description = "Add as AWS_RECOVER_ROLE_ARN secret in the backend repo (spot recovery only)."
}

output "oidc_provider_arn" {
  value       = local.oidc_provider_arn
  description = "The OIDC provider ARN (created or referenced)."
}
