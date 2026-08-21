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

# Break-glass key pair for console SSH (SG has no port 22 ingress by default). CI deploys
# happen over SSM using the box's instance role, not this key.
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

# CI writes the .env file here and the box pulls it down over SSM — keeps secrets out of
# SSM command parameters, which are stored in plaintext in command history/CloudTrail.
module "deploy_staging" {
  source          = "../modules/s3"
  bucket_name     = "${var.name_prefix}-deploy-staging"
  create_app_user = false
  force_destroy   = true
  tags            = local.common_tags
}

module "app" {
  source = "../modules/ec2-app"

  name_prefix               = var.name_prefix
  vpc_id                    = data.aws_vpc.default.id
  subnet_id                 = element(data.aws_subnets.default.ids, 0)
  ami_id                    = data.aws_ssm_parameter.ubuntu.value
  instance_type             = var.instance_type
  root_volume_gb            = var.root_volume_gb
  key_name                  = aws_key_pair.box.key_name
  use_spot                  = var.use_spot
  spot_max_price            = var.spot_max_price
  tags                      = local.common_tags
  backend_repo_url          = module.ecr.repository_urls["backend"]
  frontend_repo_url         = module.ecr.repository_urls["frontend"]
  image_tag                 = var.image_tag
  deploy_staging_bucket_arn = module.deploy_staging.bucket_arn
}

module "cicd" {
  source                    = "../modules/cicd"
  name_prefix               = var.name_prefix
  account_id                = data.aws_caller_identity.current.account_id
  github_org                = var.github_org
  github_repos              = var.github_repos
  branch                    = "dev"
  ecr_arns                  = module.ecr.repository_arns
  create_oidc_provider      = var.create_github_oidc_provider
  aws_region                = var.aws_region
  deploy_instance_id        = module.app.instance_id
  deploy_staging_bucket_arn = module.deploy_staging.bucket_arn
  tags                      = local.common_tags
}
