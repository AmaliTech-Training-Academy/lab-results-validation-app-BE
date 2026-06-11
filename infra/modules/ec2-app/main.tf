terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.40"
    }
  }
}

resource "aws_security_group" "web" {
  name_prefix = "${var.name_prefix}-sg-"
  description = "Dev box: web in from anywhere, SSH from admin only."
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

  ingress {
    description = "SSH (admin only)"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
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

# No instance role/profile: this account can't iam:PassRole, so a role can't be attached to
# EC2. The box has NO AWS identity at all — CI deploys over SSH (writes .env, passes an ECR
# login token, pulls, and starts the stack). So nothing here needs AWS credentials.

resource "aws_instance" "this" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.web.id]
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
