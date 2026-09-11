output "deploy_role_arn" {
  value       = aws_iam_role.deploy.arn
  description = "Add as AWS_DEPLOY_ROLE_ARN secret in the repos."
}

output "oidc_provider_arn" {
  value       = local.oidc_provider_arn
  description = "The OIDC provider ARN (created or referenced)."
}
