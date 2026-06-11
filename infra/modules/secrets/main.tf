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

# JWT_SECRET must be valid base64 (the app base64-decodes it for the HS256 key).
resource "random_bytes" "jwt" {
  length = 32 # 256-bit key
}

resource "aws_ssm_parameter" "jwt_secret" {
  name        = "${var.ssm_prefix}/JWT_SECRET"
  tags        = var.tags
  type        = "SecureString"
  value       = random_bytes.jwt.base64
  description = "JWT signing secret (base64)."
}
