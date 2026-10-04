from fastapi import FastAPI

app = FastAPI(title="Grading Platform AI Service")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
