data "aws_caller_identity" "current" {}

# Dev uses the account's default VPC/subnet — cheap and simple. Prod will swap in a
# dedicated network module instead; that's why this lookup lives in the env, not a module.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Latest Ubuntu 24.04 LTS (amd64), published by Canonical as a public SSM parameter.
data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

# Applied per-resource (not via provider default_tags) so we can skip resources this account
# isn't allowed to tag.
locals {
  common_tags = {
    Project     = "LabResults"
    Environment = "dev"
    owner       = "LabResults"
  }
}

# The box can't use an IAM role (this account denies iam:PassRole), so deploys happen over
# SSH instead. Terraform generates the key pair; the private key is a (sensitive) output you
# copy into the repo secret SSH_PRIVATE_KEY. CI uses it to reach the box.
resource "tls_private_key" "box" {
  algorithm = "ED25519"
}

resource "aws_key_pair" "box" {
  key_name   = "${var.name_prefix}-key"
  public_key = tls_private_key.box.public_key_openssh
  tags       = local.common_tags
}

module "ecr" {
  source      = "../modules/ecr"
  name_prefix = var.name_prefix
  repos       = ["backend", "frontend"]
  tags        = local.common_tags
}

# Stores SharePoint files per cohort; versioned so instructor edits can be diffed
# against the prior version to re-trigger validation.
module "sharepoint_files" {
  source      = "../modules/s3"
  bucket_name = var.sharepoint_bucket_name
  tags        = local.common_tags
}

module "app" {
  source = "../modules/ec2-app"

  name_prefix       = var.name_prefix
  vpc_id            = data.aws_vpc.default.id
  subnet_id         = element(data.aws_subnets.default.ids, 0)
  ami_id            = data.aws_ssm_parameter.ubuntu.value
  instance_type     = var.instance_type
  root_volume_gb    = var.root_volume_gb
  admin_cidr        = var.admin_cidr
  key_name          = aws_key_pair.box.key_name
  use_spot          = var.use_spot
  spot_max_price    = var.spot_max_price
  tags              = local.common_tags
  backend_repo_url  = module.ecr.repository_urls["backend"]
  frontend_repo_url = module.ecr.repository_urls["frontend"]
  image_tag         = var.image_tag
}

module "cicd" {
  source               = "../modules/cicd"
  name_prefix          = var.name_prefix
  account_id           = data.aws_caller_identity.current.account_id
  github_org           = var.github_org
  github_repos         = var.github_repos
  branch               = "dev"
  ecr_arns             = module.ecr.repository_arns
  create_oidc_provider = var.create_github_oidc_provider
  tags                 = local.common_tags
}
