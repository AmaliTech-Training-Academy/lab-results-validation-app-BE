variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to taggable resources."
}

variable "name_prefix" {
  type        = string
  description = "Prefix for repo names; the logical app name is appended (e.g. labresults-dev-backend)."
}

variable "repos" {
  type        = list(string)
  description = "Logical app names to create repositories for, e.g. [\"backend\", \"frontend\"]."
}

variable "max_image_count" {
  type        = number
  default     = 10
  description = "Lifecycle: keep only the most recent N images per repo."
}

variable "force_delete" {
  type        = bool
  default     = true
  description = "Allow `terraform destroy` to remove repos that still contain images (handy for dev)."
}
