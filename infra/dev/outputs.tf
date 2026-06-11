output "instance_id" {
  value       = module.app.instance_id
  description = "EC2 instance id."
}

output "public_ip" {
  value       = module.app.public_ip
  description = "Box Elastic IP. Set this as the SSH_HOST repo secret, and give it to whoever owns the RDS to whitelist on 5432."
}

output "app_url" {
  value       = "http://${module.app.public_ip}"
  description = "Open this once images are deployed."
}

output "nip_io_url" {
  value       = "http://${replace(module.app.public_ip, ".", "-")}.nip.io"
  description = "Free hostname (no domain needed)."
}

output "backend_ecr_url" {
  value = module.ecr.repository_urls["backend"]
}

output "frontend_ecr_url" {
  value = module.ecr.repository_urls["frontend"]
}

output "github_deploy_role_arn" {
  value       = module.cicd.deploy_role_arn
  description = "Set as AWS_DEPLOY_ROLE_ARN repo secret in BOTH repos."
}

output "ssh_private_key" {
  value       = tls_private_key.box.private_key_openssh
  sensitive   = true
  description = "Set as SSH_PRIVATE_KEY repo secret. Get it with: terraform output -raw ssh_private_key"
}

output "ssh_command" {
  value       = "ssh ubuntu@${module.app.public_ip}"
  description = "Manual SSH (save the private key locally first)."
}
