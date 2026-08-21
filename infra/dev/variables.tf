variable "aws_region" {
  type    = string
  default = "eu-west-1"
}

variable "name_prefix" {
  type        = string
  default     = "labresults-dev"
  description = "Drives resource names, ECR repo names, and the instance Name tag."
}

variable "instance_type" {
  type    = string
  default = "t3.small"
}

variable "root_volume_gb" {
  type    = number
  default = 20
}

variable "image_tag" {
  type    = string
  default = "dev"
}

variable "use_spot" {
  type        = bool
  default     = true
  description = "Run the dev box as a Spot instance (one-time; terminates on interruption)."
}

variable "spot_max_price" {
  type        = string
  default     = ""
  description = "Max hourly Spot price. Empty = cap at on-demand price (recommended)."
}

variable "github_org" {
  type    = string
  default = "AmaliTech-Training-Academy"
}

variable "github_repos" {
  type    = list(string)
  default = ["lab-results-validation-app-BE", "lab-results-validation-app-FE"]
}

variable "create_github_oidc_provider" {
  type        = bool
  default     = true
  description = "Create the account-level OIDC provider here (the first env). Set false in prod."
}

variable "sharepoint_bucket_name" {
  type        = string
  default     = "lab-results-validator-bucket"
  description = "S3 bucket holding SharePoint files for comparison/validation triggers."
}
