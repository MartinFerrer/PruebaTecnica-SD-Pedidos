FROM python:3.14-alpine

WORKDIR /demo
COPY populate_dummy_data.py ./populate_dummy_data.py

ENV PYTHONDONTWRITEBYTECODE=1
USER 65532:65532
ENTRYPOINT ["python", "/demo/populate_dummy_data.py"]
