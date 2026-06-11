output "ssm_prefix" {
  value       = var.ssm_prefix
  description = "Passthrough so dependents can express an explicit dependency on the secrets."
}

output "jwt_parameter_arn" {
  value       = aws_ssm_parameter.jwt_secret.arn
  description = "ARN of the JWT secret parameter."
}
