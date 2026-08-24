output "ssm_prefix" {
  value       = var.ssm_prefix
  description = "Passthrough so dependents can express an explicit dependency on the secrets."
}

output "jwt_parameter_arn" {
  value       = aws_ssm_parameter.jwt_secret.arn
  description = "ARN of the JWT secret parameter."
}

output "parameter_arns" {
  value = concat(
    [aws_ssm_parameter.jwt_secret.arn],
    [for p in aws_ssm_parameter.config : p.arn],
    [for p in aws_ssm_parameter.external_secret : p.arn]
  )
  description = "ARNs of every parameter this module manages, for scoping IAM policies."
}

output "external_secret_names_pending" {
  value       = var.external_secret_names
  description = "Reminder of which parameters still need their real value set via aws ssm put-parameter --overwrite."
}
