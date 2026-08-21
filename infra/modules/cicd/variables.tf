variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to taggable resources (not the OIDC provider)."
}

variable "name_prefix" {
  type = string
}

variable "account_id" {
  type = string
}

variable "github_org" {
  type        = string
  description = "GitHub org/owner."
}

variable "github_repos" {
  type        = list(string)
  description = "Repos allowed to assume the deploy role."
}

variable "branch" {
  type        = string
  default     = "dev"
  description = "Branch the OIDC subject is scoped to."
}

variable "ecr_arns" {
  type        = list(string)
  description = "ECR repository ARNs the role may push to."
}

variable "create_oidc_provider" {
  type        = bool
  default     = true
  description = "Create the account-level GitHub OIDC provider. Set false if one already exists."
}

variable "aws_region" {
  type        = string
  description = "Region of the deploy target, for scoping the SSM SendCommand resource ARN."
}

variable "deploy_instance_id" {
  type        = string
  description = "EC2 instance ID the deploy role is allowed to SendCommand to."
}

variable "deploy_staging_bucket_arn" {
  type        = string
  description = "ARN of the S3 bucket CI stages the .env file in before the box pulls it via SSM."
}
