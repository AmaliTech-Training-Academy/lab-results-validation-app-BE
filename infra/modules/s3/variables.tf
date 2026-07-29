variable "bucket_name" {
  type        = string
  description = "Globally-unique S3 bucket name."
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to the bucket."
}

variable "force_destroy" {
  type        = bool
  default     = false
  description = "Allow `terraform destroy` to delete the bucket even if it still contains objects/versions. Keep false to protect stored files."
}

variable "create_app_user" {
  type        = bool
  default     = true
  description = "Create a least-privilege IAM user (+ access key) for the app to do object CRUD on this bucket. Set false to manage app credentials elsewhere."
}
