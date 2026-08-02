#!/usr/bin/env bash
TOKEN=$(curl -sS -m 5 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 21600')
H="X-aws-ec2-metadata-token: ${TOKEN}"
echo "security-groups:"
curl -sS -m 5 -H "$H" http://169.254.169.254/latest/meta-data/security-groups
echo
echo "instance-id:"
curl -sS -m 5 -H "$H" http://169.254.169.254/latest/meta-data/instance-id
echo
MAC=$(curl -sS -m 5 -H "$H" http://169.254.169.254/latest/meta-data/network/interfaces/macs/ | head -1)
echo "sg-ids:"
curl -sS -m 5 -H "$H" "http://169.254.169.254/latest/meta-data/network/interfaces/macs/${MAC}security-group-ids"
echo
