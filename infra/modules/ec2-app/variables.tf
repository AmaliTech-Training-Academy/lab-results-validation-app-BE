variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to taggable resources."
}

variable "name_prefix" {
  type        = string
  description = "Used for resource names and the instance Name tag (e.g. labresults-dev)."
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "ami_id" {
  type = string
}

variable "instance_type" {
  type    = string
  default = "t3.small"
}

variable "root_volume_gb" {
  type    = number
  default = 20
}

variable "key_name" {
  type        = string
  description = "EC2 key pair name (break-glass console SSH only; CI deploys via SSM)."
}

variable "ssm_prefix" {
  type        = string
  description = "SSM Parameter Store path prefix (e.g. /labresults/dev) the box reads its .env from."
}

variable "backend_repo_url" {
  type        = string
  description = "ECR repository URL for the backend image (without tag)."
}

variable "frontend_repo_url" {
  type        = string
  description = "ECR repository URL for the frontend image (without tag)."
}

variable "image_tag" {
  type    = string
  default = "dev"
}

variable "use_spot" {
  type        = bool
  default     = false
  description = "Run as a Spot instance (one-time; terminates on interruption)."
}

variable "spot_max_price" {
  type        = string
  default     = ""
  description = "Max hourly Spot price. Empty = cap at the on-demand price."
}
