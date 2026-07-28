terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.40"
    }
  }
}

# Stores the SharePoint files pulled per cohort. Versioning is on so that when an
# instructor edits a file we keep the prior version to diff against and re-trigger
# validation on change.
resource "aws_s3_bucket" "this" {
  bucket        = var.bucket_name
  force_destroy = var.force_destroy
  tags          = var.tags
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Block every avenue of public access.
resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Encrypt at rest (SSE-S3). Cheap default hygiene for a bucket holding cohort files.
resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Least-privilege IAM user for the application. The EC2 box has no instance role (this account
# denies iam:PassRole), so the app authenticates with static access keys — this user is the
# identity behind them, scoped to object CRUD on THIS bucket only. The generated access key is
# a sensitive output; copy it into the AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY repo secrets.
resource "aws_iam_user" "app" {
  count = var.create_app_user ? 1 : 0
  name  = "${var.bucket_name}-app"
}

resource "aws_iam_user_policy" "app" {
  count = var.create_app_user ? 1 : 0
  name  = "s3-object-crud"
  user  = aws_iam_user.app[0].name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ObjectCrud"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.this.arn}/*"
      },
      {
        Sid      = "ListBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.this.arn
      }
    ]
  })
}

resource "aws_iam_access_key" "app" {
  count = var.create_app_user ? 1 : 0
  user  = aws_iam_user.app[0].name
}
