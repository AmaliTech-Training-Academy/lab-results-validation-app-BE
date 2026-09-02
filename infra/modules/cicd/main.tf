terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.40"
    }
  }
}


resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : "arn:aws:iam::${var.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

resource "aws_iam_role" "deploy" {
  name = "${var.name_prefix}-gh-deploy"
  tags = var.tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          # Allow any ref (push, PR merge, workflow_dispatch) from the allowed repos.
          "token.actions.githubusercontent.com:sub" = [
            for r in var.github_repos : "repo:${var.github_org}/${r}:*"
          ]
        }
      }
    }]
  })
}

# Spot recovery: a second, broader role that runs `terraform apply` from CI to rebuild the box
# after a Spot reclaim. Kept separate from the deploy role above so the routine image-push path
# keeps its narrow permissions — only the recovery workflow can reshape infrastructure.
#
# This role is necessarily powerful (applying this root touches EC2, EIP, IAM, SSM and ECR), so
# it is scoped to a single repo AND a single workflow ref via the OIDC subject, and it is opt-in
# per environment (create_recover_role) so prod never gets one by default.
resource "aws_iam_role" "recover" {
  count = var.create_recover_role ? 1 : 0

  name = "${var.name_prefix}-gh-recover"
  tags = var.tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          # Only the recovery workflow on the default branch, not any ref in the repo.
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_org}/${var.recover_repo}:ref:refs/heads/develop"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "recover" {
  count = var.create_recover_role ? 1 : 0

  name = "${var.name_prefix}-gh-recover-inline"
  role = aws_iam_role.recover[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Terraform's S3 backend: read/write the state object and the lock file beside it.
        Sid    = "TerraformState"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = [
          "arn:aws:s3:::${var.tfstate_bucket}/dev/terraform.tfstate",
          "arn:aws:s3:::${var.tfstate_bucket}/dev/terraform.tfstate.tflock"
        ]
      },
      {
        Sid      = "TerraformStateList"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = "arn:aws:s3:::${var.tfstate_bucket}"
      },
      {
        # Rebuilding the box: EC2 read is unscoped (describes take no resource constraint), and
        # the mutating calls are the ones Terraform needs to replace the instance and re-attach
        # the Elastic IP. Not resource-scoped because the replacement's ID is unknowable here.
        Sid    = "RebuildBox"
        Effect = "Allow"
        Action = [
          "ec2:Describe*",
          "ec2:RunInstances",
          "ec2:TerminateInstances",
          "ec2:CreateTags",
          "ec2:AllocateAddress",
          "ec2:AssociateAddress",
          "ec2:DisassociateAddress",
          "ec2:ReleaseAddress",
          "ec2:CreateSecurityGroup",
          "ec2:DeleteSecurityGroup",
          "ec2:AuthorizeSecurityGroup*",
          "ec2:RevokeSecurityGroup*",
          "ec2:ModifyInstanceAttribute",
          "ec2:CreateKeyPair",
          "ec2:DeleteKeyPair",
          "ec2:ImportKeyPair"
        ]
        Resource = "*"
      },
      {
        # The box gets an instance profile, so recovery must be able to pass that role to EC2.
        Sid    = "InstanceProfile"
        Effect = "Allow"
        Action = [
          "iam:GetRole",
          "iam:GetRolePolicy",
          "iam:GetInstanceProfile",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
          "iam:PassRole"
        ]
        Resource = [
          "arn:aws:iam::${var.account_id}:role/${var.name_prefix}-ssm-role",
          "arn:aws:iam::${var.account_id}:instance-profile/${var.name_prefix}-ssm-profile"
        ]
      },
      {
        # Terraform refreshes the SSM parameters and ECR/S3 resources in this root; reads are
        # enough for anything it does not change during a recovery.
        Sid    = "RefreshRemainingState"
        Effect = "Allow"
        Action = [
          "ssm:DescribeParameters",
          "ssm:GetParameter",
          "ssm:GetParameters",
          "ssm:GetParametersByPath",
          "ssm:ListTagsForResource",
          "ecr:DescribeRepositories",
          "ecr:ListTagsForResource",
          "ecr:GetLifecyclePolicy",
          "ecr:GetRepositoryPolicy",
          "s3:GetBucket*",
          "s3:GetEncryptionConfiguration",
          "s3:GetLifecycleConfiguration",
          "iam:ListOpenIDConnectProviders",
          "iam:GetOpenIDConnectProvider",
          "sts:GetCallerIdentity"
        ]
        Resource = "*"
      },
      {
        # Deploy the stack once the box is back.
        Sid      = "DeployAfterRecovery"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken", "ssm:SendCommand"]
        Resource = "*"
      },
      {
        Sid      = "SsmStatus"
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations", "ssm:DescribeInstanceInformation"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy" "deploy" {
  name = "${var.name_prefix}-gh-deploy-inline"
  role = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer"
        ]
        Resource = var.ecr_arns
      },
      {
        # The workflow finds the box by tag, because a Spot reclaim changes its instance ID.
        Sid      = "FindDeployTarget"
        Effect   = "Allow"
        Action   = ["ec2:DescribeInstances"]
        Resource = "*"
      },
      {
        # Consequently SendCommand can't be pinned to one instance ARN. It is still constrained
        # to this account's instances and to the one document the deploy actually uses.
        Sid    = "SsmDeploy"
        Effect = "Allow"
        Action = ["ssm:SendCommand"]
        Resource = [
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*",
          "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"
        ]
      },
      {
        Sid    = "SsmDeployStatus"
        Effect = "Allow"
        Action = [
          "ssm:GetCommandInvocation",
          "ssm:ListCommandInvocations"
        ]
        Resource = "*"
      }
    ]
  })
}
