output "instance_id" {
  value       = module.app.instance_id
  description = "EC2 instance id. Set as the DEPLOY_INSTANCE_ID repo secret (CI deploys via SSM SendCommand)."
}

output "public_ip" {
  value       = module.app.public_ip
  description = "Box Elastic IP. Give it to whoever owns the RDS to whitelist on 5432."
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

output "sharepoint_bucket_id" {
  value       = module.sharepoint_files.bucket_id
  description = "S3 bucket storing SharePoint files."
}

output "sharepoint_bucket_arn" {
  value       = module.sharepoint_files.bucket_arn
  description = "ARN of the SharePoint files bucket (scope IAM policies to this)."
}

output "sharepoint_app_access_key_id" {
  value       = module.sharepoint_files.app_access_key_id
  description = "Set via: aws ssm put-parameter --name /labresults/dev/AWS_ACCESS_KEY_ID --type SecureString --overwrite --value <this>"
}

output "sharepoint_app_secret_access_key" {
  value       = module.sharepoint_files.app_secret_access_key
  sensitive   = true
  description = "Set via: aws ssm put-parameter --name /labresults/dev/AWS_SECRET_ACCESS_KEY --type SecureString --overwrite --value $(terraform output -raw sharepoint_app_secret_access_key)"
}

output "pending_external_secrets" {
  value       = module.secrets.external_secret_names_pending
  description = "Parameters still holding the placeholder value — set each with: aws ssm put-parameter --name /labresults/dev/<NAME> --type SecureString --overwrite --value '<value>'"
}

output "github_deploy_role_arn" {
  value       = module.cicd.deploy_role_arn
  description = "Set as AWS_DEPLOY_ROLE_ARN repo secret in BOTH repos."
}

output "github_recover_role_arn" {
  value       = module.cicd.recover_role_arn
  description = "Set as AWS_RECOVER_ROLE_ARN repo secret in THIS repo (spot-recovery workflow)."
}

output "ssh_private_key" {
  value       = tls_private_key.box.private_key_openssh
  sensitive   = true
  description = "Break-glass key only (SG has no port 22 ingress by default). Get it with: terraform output -raw ssh_private_key"
}

output "ssm_session_command" {
  value       = "aws ssm start-session --target ${module.app.instance_id}"
  description = "Interactive shell on the box via SSM (no SSH/port 22 needed)."
}
