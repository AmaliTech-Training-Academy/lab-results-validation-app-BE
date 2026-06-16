aws_region    = "eu-west-1"
name_prefix   = "labresults-dev"
instance_type = "t3.small"

# Port 22 must be reachable by the GitHub runner for SSH deploys (key-only auth, no passwords).
# Restrict to your own IP for tighter security, but then CI can't SSH.
admin_cidr = "0.0.0.0/0"

image_tag = "dev"

# Spot instance: ~70% cheaper. One-time request; on interruption the (stateless) box
# terminates — re-apply to recreate, or set use_spot=false for steadier on-demand uptime.
use_spot       = true
spot_max_price = ""

github_org   = "AmaliTech-Training-Academy"
github_repos = ["lab-results-validation-app-BE", "lab-results-validation-app-FE"]

# First environment creates the account-level OIDC provider. Set false when you add prod.
create_github_oidc_provider = true
