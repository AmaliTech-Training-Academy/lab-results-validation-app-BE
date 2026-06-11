# Infrastructure

Terraform for the Lab Results Validation app, organised as reusable **modules** composed
by per-**environment** roots. The matching diagram is in
[`../docs/aws-architecture.drawio`](../docs/aws-architecture.drawio).

```
infra/
  modules/            # reusable building blocks (no environment specifics)
    ecr/              #   container registries (N repos)
    secrets/          #   generated DB/JWT secrets -> SSM Parameter Store
    ec2-app/          #   the single-box dev stack: SG + EC2 + EIP + IAM + bootstrap
    schedule/         #   EventBridge stop/start for an instance
    cicd/             #   GitHub OIDC provider + deploy role
  dev/                # DEV environment root — composes the modules (default VPC, t3.small)
  prod/               # (future) PROD root — same modules + a network module, RDS, Fargate…
  README.md
```

Each environment has its **own state** and its **own tfvars**, so applying/destroying dev
never touches prod.

## Git & repo strategy

Two app repos produce images; this folder owns where they run.

| Repo | Responsibility |
|------|----------------|
| `lab-results-validation-app-BE` (this repo) | Backend image **+ all infra** (this folder) |
| `lab-results-validation-app-FE` | Frontend image only |

- **Branch → environment:** push to `dev` → CI builds, pushes to ECR, redeploys the dev box.
  Later, `main`/tags → prod (same pipeline, prod root, prod deploy role).
- **Image tags:** every build pushes `:<git-sha>` (immutable) and moves `:dev`.
- ECR repos are per-environment (`labresults-dev-backend`, …) so environments stay isolated.

## Deploy dev

```bash
cd infra/dev
terraform init
# Edit terraform.tfvars — at minimum set admin_cidr to YOUR_IP/32
terraform apply
```

Outputs:

```bash
terraform output app_url                # http://<elastic-ip>
terraform output nip_io_url             # http://<ip-with-dashes>.nip.io  (no domain needed)
terraform output github_deploy_role_arn # -> add as a repo secret (below)
```

> First apply boots the box, but the app/frontend images aren't in ECR yet — only Redis comes
> up. Run each repo's CI once to publish images; the box pulls them on the next deploy.

## Database (shared RDS)

The app uses a **shared company RDS** — there is no Postgres container. Set the non-secret
bits in `terraform.tfvars` (`db_host`, `db_name`, `db_port`), and provide the credentials
**yourself** via SSM (they never enter Terraform/git/state):

```bash
aws ssm put-parameter --name /labresults/dev/DB_USER     --type SecureString --value '<user>' --region eu-west-1
aws ssm put-parameter --name /labresults/dev/DB_PASSWORD --type SecureString --value '<pass>' --region eu-west-1
```

Do this **before** the box boots (or re-run the deploy afterwards) — `user_data` reads these
at startup. Two things to verify on the RDS side, which this stack can't set for you:

1. **Network reachability:** the RDS security group must allow inbound `5432` from this box.
   Easiest is to whitelist the box's Elastic IP (`terraform output public_ip`). If the RDS is
   private and in a different VPC, you'll need VPC peering or to place the box in that VPC.
2. **SSL:** if the RDS enforces SSL (`rds.force_ssl=1`), add `SPRING_DATASOURCE_URL` with
   `?sslmode=require` (Spring relaxed binding overrides the built URL).

> ⚠️ **Shared DB caution:** `ddl_auto = "update"` lets Hibernate alter tables — risky on a DB
> others use. Prefer `"validate"` (schema already exists) and coordinate migrations.

## Wire up CI (both repos)

In each repo → Settings → Secrets and variables → Actions, add secret
`AWS_DEPLOY_ROLE_ARN` = the `github_deploy_role_arn` output.

The backend workflow is at [`../.github/workflows/deploy-dev.yml`](../.github/workflows/deploy-dev.yml).
Copy it into the frontend repo, changing `ECR_REPO` to `labresults-dev-frontend` and the build
context to the frontend's path.

## ⚠️ Required frontend change

The frontend nginx proxies `/api` to `host.docker.internal:8080`, which doesn't work on the
Linux box. In the composed deployment the backend is the service `app`:

```nginx
location /api/ { proxy_pass http://app:8080; }   # was host.docker.internal:8080
```

## A stateless box

No stop/start schedule in dev (that's a prod concern — the `schedule` module is ready to wire
in there). Because the database is external RDS and the box runs no Postgres, **the box is
stateless** — the only local state is Redis (refresh tokens). So Spot interruptions lose
nothing that matters; at worst, logged-in users re-authenticate. All services use
`restart: unless-stopped`, so the stack self-heals on boot. Stop the box manually whenever you
like (`aws ec2 stop-instances --instance-ids $(terraform output -raw instance_id)`); the shared
RDS is managed and durable independently of this box's lifecycle.

## Adding prod later

Create `infra/prod/` mirroring `dev/`, but:
- add a **network module** (custom VPC, public/private subnets) instead of the default VPC;
- reuse `ecr`, `secrets`, and `cicd` modules; for the data/runtime, add RDS + ElastiCache +
  ECS/Fargate modules (diagram Page 2) instead of `ec2-app`;
- set `create_oidc_provider = false` in the `cicd` module call — the provider is account-level
  and already created by dev.

## Cost

Dev runs on a **Spot** `t3.small` (`use_spot = true`): ~70% off on-demand, so roughly
$4–5/mo if always-on, less if you stop it when idle, ≈ $2/mo (EBS + Elastic IP) while stopped.
The request is **persistent** with `instance_interruption_behavior = stop`, so an interruption
stops the box (data + EIP preserved) and AWS restarts it when capacity returns — it does **not**
terminate. No RDS / ElastiCache / ALB / NAT in dev. Set `use_spot = false` for steadier uptime.

## Teardown

```bash
cd infra/dev && terraform destroy
```
