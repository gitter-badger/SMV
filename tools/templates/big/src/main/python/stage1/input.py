from smv import *
from pyspark.sql.functions import col, sum, lit

__all__ = ['EmploymentByState']

class input(SmvCsvFile):
    def path(self):
        return "input/employment/CB1200CZ11.csv"
