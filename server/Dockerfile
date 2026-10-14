FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONPATH=/opt/mathformula/server
WORKDIR /opt/mathformula
# Copy CODE ONLY. Never include server/.env, server/data, tokens, history DB, or operator files.
COPY server/mathformula /opt/mathformula/server/mathformula
RUN useradd -m -u 10001 scanner && mkdir -p /data/eod && chown -R scanner:scanner /data
USER scanner
EXPOSE 8765
CMD ["python","-m","mathformula","run"]
