output "repository_urls" {
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
  description = "Map of logical name -> repository URL, e.g. { backend = \"...\", frontend = \"...\" }."
}

output "repository_arns" {
  value       = [for r in aws_ecr_repository.this : r.arn]
  description = "All repository ARNs (used to scope CI push permissions)."
}
