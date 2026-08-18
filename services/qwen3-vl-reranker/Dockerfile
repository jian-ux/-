FROM pytorch/pytorch:2.10.0-cuda12.8-cudnn9-runtime

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models/huggingface \
    SENTENCE_TRANSFORMERS_HOME=/models/huggingface \
    HF_HUB_DISABLE_SYMLINKS_WARNING=1 \
    HF_HUB_DISABLE_XET=1 \
    TORCH_HOME=/models/torch

WORKDIR /app

COPY requirements.txt ./
RUN python -m pip install --break-system-packages --upgrade pip \
    && python -m pip install --break-system-packages -r requirements.txt

COPY app.py ./

EXPOSE 8091

CMD ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8091", "--workers", "1"]
