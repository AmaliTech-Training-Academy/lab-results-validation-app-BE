terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.40"
    }
  }
}

data "aws_region" "current" {}

resource "aws_security_group" "web" {
  name_prefix = "${var.name_prefix}-sg-"
  description = "Dev box: web in from anywhere. No SSH ingress - deploys go through SSM."
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

# SSM-only instance role: lets CI reach the box via aws ssm send-command instead of SSH, so
# no inbound port 22 rule (and no static admin IP allowlist) is needed at all.
resource "aws_iam_role" "ssm" {
  name = "${var.name_prefix}-ssm-role"
  tags = var.tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "read_config" {
  name = "${var.name_prefix}-read-config"
  role = aws_iam_role.ssm.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadAppConfig"
        Effect = "Allow"
        Action = ["ssm:GetParametersByPath", "ssm:GetParameter", "ssm:GetParameters"]
        Resource = [
          "arn:aws:ssm:*:*:parameter${var.ssm_prefix}",
          "arn:aws:ssm:*:*:parameter${var.ssm_prefix}/*"
        ]
      },
      {
        # SecureString parameters here use the AWS-managed key (no custom KMS key
        # configured), which needs an explicit decrypt grant to the caller.
        Sid      = "DecryptConfigSecrets"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = "ssm.${data.aws_region.current.name}.amazonaws.com"
          }
        }
      }
    ]
  })
}

resource "aws_iam_instance_profile" "ssm" {
  name = "${var.name_prefix}-ssm-profile"
  role = aws_iam_role.ssm.name
}

resource "aws_instance" "this" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.web.id]
  iam_instance_profile   = aws_iam_instance_profile.ssm.name
  key_name               = var.key_name != "" ? var.key_name : null

  # Spot — one-time request, default terminate-on-interruption (the only mode the RunInstances
  # API behind aws_instance supports; persistent/stop needs a launch template + ASG). The box
  # is stateless (DB is external RDS, secrets come from CI), so an interruption loses nothing —
  # it just terminates. Re-run `terraform apply` to recreate, or set use_spot=false for
  # on-demand if you need it always up.
  dynamic "instance_market_options" {
    for_each = var.use_spot ? [1] : []
    content {
      market_type = "spot"
      dynamic "spot_options" {
        for_each = var.spot_max_price != "" ? [1] : []
        content {
          max_price = var.spot_max_price
        }
      }
    }
  }

  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    backend_repo  = var.backend_repo_url
    frontend_repo = var.frontend_repo_url
    image_tag     = var.image_tag
    ssm_prefix    = var.ssm_prefix
    aws_region    = data.aws_region.current.name
  })

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_gb
    encrypted   = true
  }

  tags = merge(var.tags, {
    Name = var.name_prefix
  })
}

resource "aws_eip" "this" {
  instance = aws_instance.this.id
  domain   = "vpc"
  tags     = merge(var.tags, { Name = "${var.name_prefix}-eip" })
}
