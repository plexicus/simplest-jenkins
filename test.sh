docker run --rm \
  -v $(pwd):/mounted_volumes \
  -e MESSAGE_URL=https://api.app.dev.plexicus.ai/receive_plexalyzer_message \
  -e PLEXALYZER_TOKEN=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjbGllbnRfaWQiOiI2NWYwNzlmM2VmODk4ZTZhNmJiMzdlNWIiLCJjcmVhdGVkX2F0IjoiMjAyNC0xMS0wOFQxODowMzo0Mi4yODEyODYifQ.xLegYmnZ7Yfvky5D2riNLwyAPkw3RidkKnk2f3vBeoE \
  plexicus/plexalyzer-dev:latest \
  /venvs/plexicus-fastapi/bin/python /app/analyze.py \
  --name "simplest-jenkins2" \
  --output pretty \
  --owner "plexicus" \
  --url "https://github.com/plexicus/simplest-jenkins.git" \
  --branch "main" \
  --log_file "/mounted_volumes/plexalyzer.log" \
  --auto \
  --repository_id 686ca60f50760ae44c1579f1