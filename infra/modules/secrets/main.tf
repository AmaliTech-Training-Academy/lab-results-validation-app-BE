terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.40"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6"
    }
  }
}

# JWT_SECRET must be valid base64 (the app base64-decodes it for the HS256 key). Terraform
# generates and owns this one fully — nothing external to rotate in.
resource "random_bytes" "jwt" {
  length = 32 # 256-bit key
}

resource "aws_ssm_parameter" "jwt_secret" {
  name        = "${var.ssm_prefix}/JWT_SECRET"
  tags        = var.tags
  type        = "SecureString"
  value       = random_bytes.jwt.base64
  description = "JWT signing secret (base64). Terraform-generated."
}

# Non-secret config: real values live in code/tfvars, but they're stored in SSM alongside the
# secrets so the box builds .env from ONE source (this path) instead of splitting config
# between here and CI-injected values.
resource "aws_ssm_parameter" "config" {
  for_each    = var.config_values
  name        = "${var.ssm_prefix}/${each.key}"
  tags        = var.tags
  type        = "String"
  value       = each.value
  description = "Dev box config value: ${each.key}."
}

# External secrets Terraform can't generate (DB/mail/Azure/AWS creds). Declared here as
# placeholders so the parameter exists and its name is visible in code; set the real value
# once with:
#   aws ssm put-parameter --name <name> --type SecureString --value '<value>' --overwrite
# lifecycle.ignore_changes on value means terraform apply never overwrites what you put in.
resource "aws_ssm_parameter" "external_secret" {
  for_each    = toset(var.external_secret_names)
  name        = "${var.ssm_prefix}/${each.key}"
  tags        = var.tags
  type        = "SecureString"
  value       = "placeholder-set-me-via-put-parameter"
  description = "External secret: ${each.key}. Set the real value via aws ssm put-parameter."

  lifecycle {
    ignore_changes = [value]
  }
}
