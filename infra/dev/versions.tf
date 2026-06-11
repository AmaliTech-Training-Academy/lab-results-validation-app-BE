terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  backend "s3" {
    bucket       = "lab-results-validator-tfstate"
    key          = "dev/terraform.tfstate"
    region       = "eu-west-1"
    use_lockfile = true
  }

}

provider "aws" {
  region = var.aws_region

  # No default_tags: the DevOpsAdmin role can't tag IAM instance profiles / OIDC providers,
  # and default_tags applies to every resource with no opt-out. Instead, tags are applied
  # per-resource via local.common_tags (see main.tf) to everything EXCEPT those two.
}
