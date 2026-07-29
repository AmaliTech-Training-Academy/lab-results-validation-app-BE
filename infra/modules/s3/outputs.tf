output "bucket_id" {
  value       = aws_s3_bucket.this.id
  description = "Bucket name."
}

output "bucket_arn" {
  value       = aws_s3_bucket.this.arn
  description = "Bucket ARN (use to scope IAM permissions)."
}

output "bucket_domain_name" {
  value       = aws_s3_bucket.this.bucket_regional_domain_name
  description = "Regional domain name of the bucket."
}

output "app_access_key_id" {
  value       = var.create_app_user ? aws_iam_access_key.app[0].id : null
  description = "Access key id for the app IAM user — set as the AWS_ACCESS_KEY_ID repo secret."
}

output "app_secret_access_key" {
  value       = var.create_app_user ? aws_iam_access_key.app[0].secret : null
  sensitive   = true
  description = "Secret access key for the app IAM user. Read with: terraform output -raw <name>."
}
