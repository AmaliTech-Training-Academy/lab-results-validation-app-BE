variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to taggable resources."
}

variable "ssm_prefix" {
  type        = string
  description = "SSM path prefix"
}
